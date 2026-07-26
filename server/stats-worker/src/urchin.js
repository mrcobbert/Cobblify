/**
 * Urchin blacklist integration (Coral v3 API, https://api.urchin.gg).
 *
 * v3 shapes handled here: the key travels as an `X-API-Key` request header (never in a URL),
 * errors are real HTTP statuses (401 with an EMPTY body for a bad key, 404 for an unknown
 * player, 429 for the rate limit), tag fields are `tag_type` / unix-millisecond `added_on`,
 * and the batch endpoint takes UUIDs inside a `uuids` array (dashed or undashed), echoing
 * them back undashed-lowercase as the response keys. A batch entry that was checked and is
 * clean is PRESENT with an empty tag list; an entry the upstream skipped is OMITTED entirely
 * - those two must never be conflated (absent is "unknown", not "clean").
 *
 * Read-only, owner-only, fail-closed. The API key lives ONLY in this Worker (URCHIN_KEY
 * secret, else KV "urchin:cfg:key"); redirects are disabled so a keyed request is never
 * replayed off-origin, and upstream URLs are never logged.
 *
 * Gating (all must hold before any Coral traffic):
 *   - STATS_TOKEN configured AND matched (owner-only; a personal key must not serve strangers)
 *   - X-BWQOL-Urchin: 1 opt-in header (client sends it only for identity-confirmed tasks)
 *   - STATS_KV bound (the backoff/disabled state lives there; no KV -> inert)
 *
 * Cache: 24 h retention / 6 h freshness per player UUID; stale entries are served only when
 * a refetch cannot run (backoff/disabled/failure). Negative results cache the same way.
 * Only {type, reason, addedOn, expiresAt} are retained - reporter identity fields and the
 * never-displayed info/account types are dropped before storage (data minimization).
 *
 * Rate ceiling: reactive only. v3 allows 600 requests / rolling 5 min for a personal key and
 * sends no rate-limit headers, while a whole lobby is ONE batch request - so the only signal
 * worth acting on is a 429, which arms BACKOFF_MS (401/403 arms DISABLED_RETRY_MS).
 *
 * PRIVACY NOTE: only identity-confirmed, active-game UUIDs may ever reach the batch
 * endpoint; the route tests assert the exact upstream UUID array.
 */

const CORAL_BASE_DEFAULT = "https://api.urchin.gg";

const FRESH_MS = 6 * 60 * 60 * 1000; // serve without refetch
const RETAIN_SEC = 24 * 60 * 60; // KV expirationTtl (stale window for outages)
const BACKOFF_MS = 5 * 60 * 1000; // after a Coral 429
const DISABLED_RETRY_MS = 60 * 60 * 1000; // after a Coral 401/403 (bad/locked key)
const MAX_BATCH_UUIDS = 100; // v3's documented cap for POST /v3/players

const UUID_RE = /^[0-9a-f]{32}$/;

/**
 * Per-isolate in-flight dedupe: uuid -> { promise, startedAt }.
 *
 * Entries carry a creation time because they can be ORPHANED. workerd may cancel a request
 * mid-flight (the single-player route awaits tagsForUuids WITHOUT ctx.waitUntil, and the Java
 * client hangs up at 15 s), dropping the owner's continuation so its `finally` never runs.
 * With an identity-matched delete and no age check, that uuid stays claimed for the isolate's
 * lifetime - hours - and every later lookup either awaits a promise nothing will ever settle
 * or replays one frozen response, re-writing it to KV with a fresh fetchedAt so it never ages
 * out. Silent, per-player and unbounded. Treating an entry older than INFLIGHT_TTL_MS as
 * absent restores self-healing without reintroducing duplicate batches for a live request.
 */
const inflight = new Map();
const INFLIGHT_TTL_MS = 30 * 1000; // longer than any real Coral round trip, far under an isolate's life

function inflightGet(uuid, nowMs) {
  const e = inflight.get(uuid);
  if (!e) return null;
  if (nowMs - e.startedAt >= INFLIGHT_TTL_MS) {
    inflight.delete(uuid);
    console.log("URCHIN_INFLIGHT_STALE_EVICTED");
    return null;
  }
  return e.promise;
}

/** Test-only inspection of the in-flight map - the one piece of module state where a leak is a
 *  permanent per-player outage, so a test must be able to see and clear it. */
export function inflightSize() {
  return inflight.size;
}
export function resetInflight() {
  inflight.clear();
}

let isolateKeyRejectedAt = 0; // time-bounded: re-consult KV after DISABLED_RETRY_MS
let isolateKeyRejectStreak = 0; // consecutive rejections; any success resets it

// A trailing slash on the configured base would produce "//v3/..." - which Coral answers with
// a 404, i.e. the worker would report urchinNotFound for EVERY player instead of surfacing a
// misconfiguration. Normalize so a config typo can never masquerade as "player not found".
function coralBase(env) {
  const raw = (env && env.URCHIN_BASE) || CORAL_BASE_DEFAULT;
  return String(raw).replace(/\/+$/, "");
}

/** Lowercase, undashed canonical UUID or null. */
export function normalizeUuid(raw) {
  if (typeof raw !== "string") return null;
  const s = raw.trim().toLowerCase().replace(/-/g, "");
  return UUID_RE.test(s) ? s : null;
}

/** Strip color/control chars, collapse whitespace, cap length. */
export function sanitizeText(raw, max) {
  if (typeof raw !== "string") return "";
  return raw
    .replace(/\u00a7./g, "")
    // ALL Unicode format chars (Cf: bidi controls, zero-width, BOM, ALM, ...) plus
    // C0 + DEL + C1 controls
    .replace(/[\p{Cf}\u0000-\u001f\u007f-\u009f]/gu, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, max || 120);
}

/**
 * The legacy v2 API intermittently injected a synthetic tag (observed as type "caution")
 * whose reason was a shutdown notice addressed to THIS service, not a report about the
 * player. Left alone such a tag makes clean players look tagged and replaces the reason text
 * in the alert hover. v3 is not known to do this, but the ingest-side drop stays as cheap
 * defense against any future upstream nag. (No read-side filter is needed: the cache
 * namespace bump orphaned every entry written under v2.)
 *
 * ANCHORED at the start of the (already trimmed) reason: an injected notice is the whole
 * reason, whereas a genuine report only ever QUOTES such wording mid-sentence. Unanchored,
 * this silently deleted any real cheater report that happened to mention the deprecation -
 * a false positive with no trace anywhere.
 */
const SERVICE_NOTICE_RE = /^(?:notice for the developer of this service|the urchin api is deprecated)/i;

function isServiceNotice(tag) {
  return Boolean(tag) && SERVICE_NOTICE_RE.test(String(tag.reason || ""));
}

/**
 * Map an upstream tag list to our stored shape, dropping reporter fields + info/account.
 * Reads the v3 shape ({tag_type, added_on: unix ms}) and still accepts the older
 * ({type, added_on: ISO string}) spelling so the parsing contract survives an API migration.
 */
export function mapTags(coralTags, nowMs) {
  if (!Array.isArray(coralTags)) return [];
  const out = [];
  for (const t of coralTags) {
    if (!t || typeof t !== "object") continue;
    const type = sanitizeText(String(t.tag_type || t.type || ""), 40).toLowerCase();
    if (!type || type === "info" || type === "account") continue;
    // expires_at: absent/null = never expires; present but unparseable or non-positive =
    // malformed upstream data -> drop the tag defensively rather than pin it forever.
    let expiresAt = null;
    if (t.expires_at !== undefined && t.expires_at !== null) {
      const e = toMs(t.expires_at);
      if (e == null || e <= 0) continue;
      expiresAt = e;
    }
    if (expiresAt != null && expiresAt <= nowMs) continue;
    const reason = sanitizeText(String(t.reason || ""), 120);
    if (isServiceNotice({ reason })) continue;
    out.push({
      type,
      reason,
      addedOn: toMs(t.added_on) || 0,
      expiresAt,
    });
  }
  return out;
}

/** Unix ms: v3 sends finite integers (passed through); ISO-8601 strings still parse. */
function toMs(v) {
  if (Number.isFinite(v)) return v;
  if (typeof v === "string" && v) {
    const p = Date.parse(v.endsWith("Z") || /[+-]\d\d:\d\d$/.test(v) ? v : v + "Z");
    if (Number.isFinite(p)) return p;
  }
  return null;
}

/** Drop tags whose expiry has passed since storage. */
export function filterActive(tags, nowMs) {
  if (!Array.isArray(tags)) return [];
  return tags.filter((t) => t && (t.expiresAt == null || t.expiresAt > nowMs));
}

/**
 * An entry is fresh while (a) it is younger than FRESH_MS AND (b) none of its stored tags
 * has expired since it was written - the earliest expiresAt ends effective freshness so a
 * replacement/new tag is refetched at that instant instead of hiding behind checked-empty.
 */
export function entryFresh(entry, nowMs) {
  if (!entry || !Number.isFinite(entry.fetchedAt)) return false;
  if (nowMs - entry.fetchedAt >= FRESH_MS) return false;
  for (const t of entry.tags || []) {
    if (t && t.expiresAt != null && t.expiresAt <= nowMs) return false;
  }
  return true;
}

// ---- gating ---------------------------------------------------------------

/** Owner token valid AND configured (never open). */
function hasValidToken(request, env) {
  const expected = env && env.STATS_TOKEN;
  if (!expected) return false;
  return request.headers.get("X-BedwarsQol-Token") === expected;
}

/** Owner authentication + explicit opt-in for Urchin DATA routes (capability is separate). */
export function urchinAllowed(request, env) {
  return Boolean(hasValidToken(request, env) && request.headers.get("X-BWQOL-Urchin") === "1");
}

/**
 * Capability: without STATS_KV the backoff/disabled state cannot exist, so data
 * routes must make zero Coral calls - but an authenticated opted-in owner gets resolved
 * "unavailable" metadata rather than a misleading authentication error.
 */
export function urchinCapable(env) {
  return Boolean(env && env.STATS_KV);
}

/** Gate for the set-key route: token + KV only (no opt-in, no master dependency). */
export function keyRouteAllowed(request, env) {
  return Boolean(hasValidToken(request, env) && env && env.STATS_KV);
}

async function activeKey(env) {
  if (env && env.URCHIN_KEY) return { key: env.URCHIN_KEY, fromSecret: true };
  if (env && env.STATS_KV) {
    try {
      const k = await env.STATS_KV.get("urchin:cfg:key");
      if (k) return { key: k, fromSecret: false };
    } catch (_) { /* ignore */ }
  }
  return { key: null, fromSecret: false };
}

// ---- cache ----------------------------------------------------------------

// v2 namespace = Coral v3 entries. The bump orphans every entry written against the legacy
// v2 API (different tag field names, and the poisoned shutdown-notice rows) instead of
// letting them hide the cutover behind 6 h of freshness.
function tagsL1Key(uuid) {
  return new Request(`https://bedwarsqol.internal/cache/urchin/v2/${uuid}`);
}
function tagsL2Key(uuid) {
  return `urchin:v2:${uuid}`;
}

async function readEntry(uuid, env, ctx) {
  const cache = caches.default;
  try {
    const l1 = await cache.match(tagsL1Key(uuid));
    if (l1) return await l1.json();
  } catch (_) { /* ignore */ }
  try {
    const kv = await env.STATS_KV.get(tagsL2Key(uuid), "json");
    if (kv) {
      if (ctx) {
        ctx.waitUntil(
          cache
            .put(
              tagsL1Key(uuid),
              new Response(JSON.stringify(kv), {
                headers: {
                  "content-type": "application/json",
                  "cache-control": `public, max-age=${Math.floor(FRESH_MS / 1000)}`,
                },
              })
            )
            .catch(() => {})
        );
      }
      return kv;
    }
  } catch (_) { /* ignore */ }
  return null;
}

function writeEntry(uuid, entry, env, ctx) {
  const body = JSON.stringify(entry);
  const put = caches.default.put(
    tagsL1Key(uuid),
    new Response(body, {
      headers: {
        "content-type": "application/json",
        "cache-control": `public, max-age=${Math.floor(FRESH_MS / 1000)}`,
      },
    })
  );
  const kvPut = env.STATS_KV.put(tagsL2Key(uuid), body, { expirationTtl: RETAIN_SEC }).catch(() => {});
  if (ctx) {
    ctx.waitUntil(put.catch(() => {}));
    ctx.waitUntil(kvPut);
  }
}

// ---- backoff / disabled state ----------------------------------------------

let isolateBackoffUntil = 0; // immediate local record; KV write is best-effort cross-isolate

/**
 * Every path that stops a Coral call says so, once per call, with a one-word reason. Anything
 * that suppresses upstream traffic silently reproduces the outage this integration already
 * shipped once: a live-looking Worker serving permanent false-cleans with nothing in `wrangler
 * tail` to see. No key and no player data - the reason alone is the diagnostic.
 */
function suppressed(reason) {
  console.log("URCHIN_SUPPRESSED " + reason);
  return true;
}

async function isBlocked(env, nowMs) {
  if (isolateKeyRejectedAt && nowMs - isolateKeyRejectedAt < DISABLED_RETRY_MS) return suppressed("key_rejected");
  if (nowMs < isolateBackoffUntil) return suppressed("isolate_backoff");
  try {
    const [backoff, disabled] = await Promise.all([
      env.STATS_KV.get("urchin:cfg:backoff"),
      env.STATS_KV.get("urchin:cfg:disabled"),
    ]);
    if (backoff && nowMs - Number(backoff) < BACKOFF_MS) return suppressed("kv_backoff");
    if (disabled && nowMs - Number(disabled) < DISABLED_RETRY_MS) return suppressed("kv_disabled");
  } catch (_) {
    // Fail OPEN. Failing closed here armed a 5 min isolate backoff off a single transient KV
    // READ, which then marked every uuid in the request unavailable - sticky client-side, so
    // real cheaters read as clean for the cache entry's whole lifetime, with no log and
    // nothing written to KV to inspect afterwards. The downside of failing open is at worst
    // one Coral call during a live backoff, answered with a 429 that arms the reactive
    // control we already trust.
    console.log("URCHIN_BLOCKSTATE_READ_FAILED");
    return false;
  }
  return false;
}

function noteBackoff(env, ctx, nowMs) {
  isolateBackoffUntil = Math.max(isolateBackoffUntil, nowMs + BACKOFF_MS);
  const put = env.STATS_KV.put("urchin:cfg:backoff", String(nowMs), { expirationTtl: 600 });
  if (ctx) ctx.waitUntil(put.catch(() => {}));
}

/**
 * api.urchin.gg sits behind Cloudflare, so a WAF/bot-management 403 - or a 401 during an
 * upstream deploy - is indistinguishable from a bad key at this layer. Arming the 1 h
 * cross-isolate disable off ONE of those turned a momentary edge hiccup into an hour of
 * suppressed lookups, so it takes two IN A ROW (any successful round trip clears the streak).
 *
 * Logged EVERY time, not once per isolate: the once-per-isolate log is precisely what made the
 * previous investigation blind - a `wrangler tail` started after the first rejection saw an
 * apparently healthy Worker.
 */
const KEY_REJECT_TRIP = 2;

function noteKeyRejected(env, ctx, nowMs) {
  isolateKeyRejectStreak++;
  console.log("URCHIN_KEY_REJECTED streak=" + isolateKeyRejectStreak);
  if (isolateKeyRejectStreak < KEY_REJECT_TRIP) return;
  isolateKeyRejectedAt = nowMs;
  const put = env.STATS_KV.put("urchin:cfg:disabled", String(nowMs), { expirationTtl: 7200 });
  if (ctx) ctx.waitUntil(put.catch(() => {}));
}

// ---- upstream-failure circuit breaker --------------------------------------

/**
 * A 429 arms BACKOFF_MS and a 401/403 arms DISABLED_RETRY_MS, but a plain upstream failure
 * (502/503/timeout/unparseable body - the v3 spec documents 502 and 503 as real conditions)
 * arms nothing and is never cached, so during a Coral outage every lobby re-issues a fresh
 * POST /v3/players. This bounds that.
 *
 * Deliberately ISOLATE-LOCAL, like isolateBackoffUntil/isolateKeyRejectedAt: two module
 * numbers, zero KV reads and zero KV writes. The predecessor here (a KV "budget window") was
 * removed precisely because it hammered one hot key and was fail-closed; nothing about this
 * may reintroduce either property.
 *
 * It fails OPEN by construction: the state only ever suppresses calls for at most
 * FAIL_COOLDOWN_MS, any successful round trip clears it, and an unset/NaN value never
 * compares true. A tripped breaker returns the ordinary "error" status rather than routing
 * through isBlocked, so callers keep the existing transient semantics (stale ->
 * unavailable+stale, otherwise omitted and RETRYABLE) instead of the sticky "unavailable"
 * that a hard block emits - the client treats urchinUnavailable as resolved forever.
 *
 * 3 consecutive failures: one blip must not stop anything, and a whole lobby is a single
 * batch request, so 3 in a row is an outage rather than noise. 60 s: long enough to stop the
 * retry storm, short enough to recover in-game unnoticed, and well under the 5 min 429 backoff
 * (a 5xx is far more likely to be transient than a rate limit).
 */
const FAIL_STREAK_TRIP = 3;
const FAIL_COOLDOWN_MS = 60 * 1000;
let isolateFailStreak = 0;
let isolateBreakerUntil = 0;
let isolateLastFailAt = 0;

function breakerFailure(nowMs) {
  // Decay: "3 consecutive failures" is only evidence of an outage when they are close
  // together. Without this, three unrelated blips hours apart trip a 60 s suppression for no
  // reason - and the streak survives across every request the isolate ever serves.
  if (isolateLastFailAt && nowMs - isolateLastFailAt > FAIL_COOLDOWN_MS) isolateFailStreak = 0;
  isolateLastFailAt = nowMs;
  if (++isolateFailStreak >= FAIL_STREAK_TRIP) {
    isolateFailStreak = 0;
    isolateBreakerUntil = nowMs + FAIL_COOLDOWN_MS;
    console.log("URCHIN_BREAKER_TRIPPED cooldown_ms=" + FAIL_COOLDOWN_MS);
  }
  return { status: "error" };
}

/** Any completed round trip (including a name-route 404) proves the upstream is answering AND
 *  that the key was accepted, so it clears both streaks. */
function breakerSuccess() {
  isolateFailStreak = 0;
  isolateBreakerUntil = 0;
  isolateLastFailAt = 0;
  isolateKeyRejectStreak = 0;
}

// ---- Coral calls ----------------------------------------------------------

/**
 * `opts.nameRoute` marks the ONE path where a 404 is real data ("no such player").
 * Passed explicitly rather than sniffed from `path`, because getting it wrong is silent: a 404
 * on POST /v3/players can only mean the endpoint moved or the path is wrong, and scoring that
 * as upstream health (breakerSuccess + "notfound") produces a 100 %-failure, full-rate,
 * zero-signal loop - every lookup goes upstream, every one comes back retryable, nothing
 * caches, and the breaker is RESET on each call so it can never engage. That is the exact user
 * experience of the outage this integration already shipped once, and Coral is versioned 0.1.0.
 */
async function coralFetch(env, path, init, opts) {
  // Tripped breaker: report the same transient failure the callers already handle, without
  // spending a subrequest. Not counted as a failure - it never reached the upstream.
  if (Date.now() < isolateBreakerUntil) {
    suppressed("breaker");
    return { status: "error" };
  }
  const { key } = await activeKey(env);
  if (!key) return { status: "nokey" };
  let res;
  try {
    // The key travels ONLY as a request header - never in a URL, where it could be logged by
    // an intermediary or leaked via Referer. workerd only supports "follow"/"manual";
    // "manual" + explicit 3xx failure means the keyed request is never replayed off-origin.
    res = await fetch(`${coralBase(env)}${path}`, {
      ...init,
      headers: { ...(init && init.headers), "X-API-Key": key },
      redirect: "manual",
    });
  } catch (e) {
    return breakerFailure(Date.now());
  }
  if (res.status >= 300 && res.status < 400) {
    try { await res.body?.cancel(); } catch (_) {}
    return breakerFailure(Date.now());
  }
  // Status is classified BEFORE any parse: v3's 401 body is empty, not JSON. Every non-2xx
  // branch cancels the body it will never read - v3 routes far more traffic through these
  // (real 401/404/429 statuses) than v2 did, and an undrained body holds the connection.
  if (res.status === 429) {
    try { await res.body?.cancel(); } catch (_) {}
    return { status: "ratelimited" };
  }
  if (res.status === 401 || res.status === 403) {
    try { await res.body?.cancel(); } catch (_) {}
    return { status: "rejected" };
  }
  if (res.status === 404) {
    try { await res.body?.cancel(); } catch (_) {}
    if (opts && opts.nameRoute) {
      breakerSuccess();
      return { status: "notfound" };
    }
    // Batch route: the endpoint moved or the path is wrong. A real failure, never "clean".
    console.log("URCHIN_BATCH_404 endpoint_moved_or_path_wrong");
    return breakerFailure(Date.now());
  }
  if (!res.ok) {
    try { await res.body?.cancel(); } catch (_) {}
    return breakerFailure(Date.now());
  }
  let json;
  try { json = await res.json(); } catch (_) { return breakerFailure(Date.now()); }
  breakerSuccess();
  return { status: "ok", json };
}

/**
 * Resolve tags for canonical UUIDs. Returns Map<uuid, result> whose `state` picks one of the
 * three client-visible shapes (see resultFields; the client semantics are load-bearing):
 *
 *   "ok"          -> urchinChecked. Concluded. RESOLVED client-side (never refetched).
 *   "unavailable" -> urchinUnavailable. Also RESOLVED client-side, and therefore reserved for
 *                    the one DURABLE off-state: no key configured at all.
 *   "stale"       -> tags with NO flag. Displayed AND still retried - the right shape whenever
 *                    we hold old data but could not refresh it.
 *   (absent)      -> no urchin fields at all. Retried, nothing displayed.
 *
 * Anything transient (backoff, key rejection, 5xx, tripped breaker, a uuid the batch omitted)
 * must therefore end in "stale" or absence. Emitting urchinUnavailable there makes
 * UrchinResult.resolved() true, StatsCache pins urchinResolved for the entry's lifetime, and
 * the player reads as clean in the GUI forever - a permanent false-clean from a momentary blip.
 */
export async function tagsForUuids(rawUuids, env, ctx) {
  const nowMs = Date.now();
  const out = new Map();
  const uuids = [];
  for (const raw of rawUuids) {
    const u = normalizeUuid(raw);
    if (u && !uuids.includes(u)) uuids.push(u);
  }
  if (uuids.length === 0) return out;

  const { key } = await activeKey(env);
  const blocked = key ? await isBlocked(env, nowMs) : true;

  const misses = [];
  for (const u of uuids) {
    const entry = await readEntry(u, env, ctx);
    if (!key) {
      // No key -> Urchin is off entirely: no cached tags either (a cleared key must not
      // keep old accusations displayable anywhere).
      out.set(u, { state: "unavailable", tags: [] });
    } else if (entryFresh(entry, nowMs)) {
      out.set(u, { state: "ok", tags: filterActive(entry.tags, nowMs) });
    } else if (blocked) {
      // Cannot refetch right now - but a backoff/disable is TRANSIENT, so this must stay
      // retryable: stale tags with no flag, or nothing at all. (The durable keyless case is
      // handled above and is the only "unavailable" this function emits.)
      if (entry) out.set(u, { state: "stale", tags: filterActive(entry.tags, nowMs) });
    } else {
      misses.push({ uuid: u, stale: entry });
    }
  }
  if (misses.length === 0) return out;

  // In-flight dedupe within the isolate. The promise each miss will await is bound HERE,
  // synchronously, so a later `inflight` mutation can never leave a miss promise-less (which
  // would fabricate a failure that never happened).
  const awaiting = new Map(); // uuid -> promise this invocation will settle against
  const toFetch = [];
  for (const m of misses) {
    const existing = inflightGet(m.uuid, nowMs);
    if (existing) { awaiting.set(m.uuid, existing); continue; }
    // Over MAX_BATCH_UUIDS: left unclaimed and unresolved (-> omitted -> the client retries),
    // rather than marked in-flight against a request that will not carry it.
    if (toFetch.length < MAX_BATCH_UUIDS) toFetch.push(m);
  }
  let batchPromise = null;
  if (toFetch.length > 0) {
    // v3 batch: UUIDs travel inside the `uuids` array (100 max) and are echoed back
    // undashed-lowercase as the response keys.
    batchPromise = coralFetch(env, "/v3/players", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ uuids: toFetch.map((m) => m.uuid) }),
    });
    for (const m of toFetch) {
      inflight.set(m.uuid, { promise: batchPromise, startedAt: nowMs });
      awaiting.set(m.uuid, batchPromise);
    }
  }

  const settle = async (m) => {
    const p = awaiting.get(m.uuid);
    // No promise = this uuid was never sent (over the batch cap). "Unknown", not a failure:
    // omit it so the client retries instead of caching or reporting a resolution.
    if (!p) return null;
    const r = await p;
    if (r.status === "ok") {
      const players = (r.json && r.json.players) || {};
      // Response keys re-normalized before matching (casing/dashes undocumented).
      // Only a genuine array counts as an answer: a null / non-array value would otherwise
      // flow through mapTags to [] and be CACHED as a clean player on malformed upstream data.
      // Treat it exactly like an absent entry.
      const byUuid = new Map();
      for (const k of Object.keys(players)) {
        const nk = normalizeUuid(k);
        if (nk && Array.isArray(players[k])) byUuid.set(nk, players[k]);
      }
      // PRESENT with an empty list = checked and clean (conclusive, cacheable). ABSENT =
      // upstream skipped this uuid; that is unknown, NOT clean - never cache it as such, and
      // never resolve it: absent-with-no-stale is omitted entirely, absent-with-stale ships
      // its stale tags flagless so they show while the client keeps retrying.
      if (!byUuid.has(m.uuid)) {
        return m.stale ? { state: "stale", tags: filterActive(m.stale.tags, nowMs) } : null;
      }
      const tags = mapTags(byUuid.get(m.uuid), nowMs);
      writeEntry(m.uuid, { tags, fetchedAt: nowMs }, env, ctx);
      return { state: "ok", tags: filterActive(tags, nowMs) };
    }
    // The key vanished between the pre-check and the call: a DURABLE off-state, and the clear
    // contract says a keyless worker displays nothing, not even stale tags.
    if (r.status === "nokey") return { state: "unavailable", tags: [] };
    if (r.status === "ratelimited") noteBackoff(env, ctx, nowMs);
    if (r.status === "rejected") noteKeyRejected(env, ctx, nowMs);
    // Everything left is transient - a 429, a rejection that may well be a Cloudflare WAF 403,
    // a 5xx/timeout, or a tripped breaker. Never sticky: stale tags flagless (shown AND
    // retried), no stale -> omitted (retried, nothing shown).
    if (m.stale) return { state: "stale", tags: filterActive(m.stale.tags, nowMs) };
    return null;
  };

  await Promise.all(
    misses.map(async (m) => {
      try {
        const r = await settle(m);
        if (r) out.set(m.uuid, r);
      } finally {
        // Only the OWNER retires its own entry. Deleting unconditionally would drop an entry
        // another invocation put there, letting a third caller issue a duplicate batch while
        // the first is still pending. (An owner that never gets here - a canceled request -
        // is covered by the INFLIGHT_TTL_MS age check in inflightGet.)
        const held = inflight.get(m.uuid);
        if (batchPromise && held && held.promise === batchPromise) inflight.delete(m.uuid);
      }
    })
  );
  return out;
}

/** Stale fallback for the manual route: name alias -> cached entry (may be empty). */
async function staleForName(name, env, ctx) {
  try {
    const aliased = await env.STATS_KV.get(`urchin:name:v1:${name.toLowerCase()}`);
    const u = normalizeUuid(aliased);
    if (u) {
      const entry = await readEntry(u, env, ctx);
      if (entry) return { uuid: u, entry };
    }
  } catch (_) { /* ignore */ }
  return null;
}

/** Manual name lookup (GET /v3/player/tags?player=) - the ONLY name-resolving upstream path. */
export async function tagsForName(name, env, ctx) {
  const nowMs = Date.now();
  const { key } = await activeKey(env);
  const cached = await staleForName(name, env, ctx);

  if (!key) {
    // Keyless: Urchin is off entirely - no cached tags either.
    return { state: "unavailable", tags: [] };
  }
  // Fresh cache reuse: repeated manual lookups inside the 6 h window spend no Coral request.
  if (cached && entryFresh(cached.entry, nowMs)) {
    return { state: "ok", tags: filterActive(cached.entry.tags, nowMs), uuid: cached.uuid };
  }
  if (await isBlocked(env, nowMs)) {
    return { state: "unavailable", tags: cached ? filterActive(cached.entry.tags, nowMs) : [], stale: !!cached };
  }

  // nameRoute: the only path where a 404 means "no such player" rather than "wrong endpoint".
  const r = await coralFetch(env, `/v3/player/tags?player=${encodeURIComponent(name)}`, { method: "GET" }, { nameRoute: true });
  if (r.status === "ok") {
    // v3 answers an unknown name with a real 404 (-> "notfound" below), so a 2xx here is a
    // tag result; a uuid that fails to normalize just means it cannot be cached/aliased.
    const uuid = normalizeUuid(r.json && r.json.uuid);
    const tags = mapTags((r.json && r.json.tags) || [], nowMs);
    if (uuid) {
      writeEntry(uuid, { tags, fetchedAt: nowMs }, env, ctx);
      const alias = env.STATS_KV.put(`urchin:name:v1:${name.toLowerCase()}`, uuid, { expirationTtl: RETAIN_SEC });
      if (ctx) ctx.waitUntil(alias.catch(() => {}));
    }
    return { state: "ok", tags: filterActive(tags, nowMs), uuid };
  }
  if (r.status === "notfound") return { state: "notfound", tags: [] };
  if (r.status === "ratelimited") noteBackoff(env, ctx, nowMs);
  if (r.status === "rejected") noteKeyRejected(env, ctx, nowMs);

  // Failure (timeout/5xx/429/rejected): serve stale via the name alias when possible.
  if (cached) return { state: "unavailable", tags: filterActive(cached.entry.tags, nowMs), stale: true };
  return { state: "unavailable", tags: [] };
}

/**
 * Shape a per-player result into response fields (urchin/urchinChecked/urchinUnavailable).
 *
 * DO NOT add a flag to the fall-through. Both urchinChecked and urchinUnavailable make the
 * client's UrchinResult.resolved() true, which pins the cache entry as resolved for its whole
 * lifetime; state "stale" deliberately emits tags with NEITHER, so they are displayed while the
 * client keeps retrying. A "stale" result whose tags all expired therefore yields only
 * urchinUuid, which the client reads as "no resolution fields" - retryable, exactly as intended.
 */
export function resultFields(result, uuid) {
  if (!result) return {};
  const fields = {};
  // Unambiguous provenance for the client merge: the canonical UUID this result belongs
  // to. Names are NOT a safe join key (case-varied duplicates collide).
  if (uuid) fields.urchinUuid = uuid;
  if (result.state === "ok" || result.state === "notfound") fields.urchinChecked = true;
  if (result.state === "notfound") fields.urchinNotFound = true;
  if (result.state === "unavailable") fields.urchinUnavailable = true;
  if (Array.isArray(result.tags) && result.tags.length > 0) {
    fields.urchin = { tags: result.tags.map(({ type, reason, addedOn, expiresAt }) => ({
      type, reason, addedOn, ...(expiresAt != null ? { expiresAtMs: expiresAt } : {}),
    })) };
  }
  return fields;
}

/** POST /urchin/key: {key: "..."} sets, {key: null} clears. Never echoes the key. */
export async function handleKeySet(request, env, ctx) {
  if (!env || !env.STATS_KV) {
    return json({ success: false, error: "kv_required" }, 503);
  }
  if (!keyRouteAllowed(request, env)) {
    return json({ success: false, error: "unauthorized" }, 403);
  }
  if (env.URCHIN_KEY) {
    return json({ success: false, error: "key_managed_by_secret" }, 409);
  }
  let body;
  try { body = await request.json(); } catch (_) {
    return json({ success: false, error: "bad_request" }, 400);
  }
  const key = body && Object.prototype.hasOwnProperty.call(body, "key") ? body.key : undefined;
  if (key === undefined || (key !== null && (typeof key !== "string" || key.length < 8 || key.length > 200))) {
    return json({ success: false, error: "bad_request" }, 400);
  }
  // Partial-mutation contract: auxiliary state (backoff/disabled) is cleaned BEFORE the key
  // mutation, so a failure response always means the key itself is unchanged - the client
  // can trust "failure = nothing happened" and "success = key state + cleanup committed".
  try {
    await env.STATS_KV.delete("urchin:cfg:backoff");
    await env.STATS_KV.delete("urchin:cfg:disabled");
    if (key === null) await env.STATS_KV.delete("urchin:cfg:key");
    else await env.STATS_KV.put("urchin:cfg:key", key);
  } catch (_) {
    return json({ success: false, error: "kv_write_failed" }, 503);
  }
  isolateKeyRejectedAt = 0;
  isolateKeyRejectStreak = 0;
  isolateBackoffUntil = 0;
  isolateBreakerUntil = 0; // a key change is an explicit "try again now"
  isolateFailStreak = 0;
  isolateLastFailAt = 0;
  return json({ success: true });
}

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status: status || 200,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
