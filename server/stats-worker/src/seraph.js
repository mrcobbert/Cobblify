/**
 * Seraph blacklist integration (https://api.seraph.si).
 *
 * Sibling provider to urchin.js, cloned from its structure but adapted to the Seraph contract
 * (approved plan §7a, §7d). Read-only, per-identity, fail-closed: each authenticated identity
 * (worker.js authenticate) has its OWN key slot (`seraph:cfg:key:<identity>`; the SERAPH_KEY
 * secret covers the OWNER identity only) and its OWN backoff/disabled/breaker state - one
 * user's bad key or 429 never suppresses another user's lookups. Nothing reads the legacy
 * unsuffixed `seraph:cfg:*` names. The per-isolate in-flight dedupe stays keyed by UUID only
 * (shared by policy, like urchin's caches); the in-flight promise records the EXECUTING
 * identity and a piggybacking requester shares success-class results only - Seraph has no
 * stale cache, so a piggybacked failure is always full omission (retryable).
 *
 * Endpoint (UUID-only): GET https://api.seraph.si/{uuid}/blacklist. Seraph has NO batch and NO
 * name-lookup endpoint, so - unlike urchin.js - this module resolves each canonical UUID with its
 * own GET and exposes NO tagsForName() (the /seraph/<name> manual route has no upstream to call).
 *
 * Auth: the API key travels in a REQUEST HEADER on every call (SERAPH_AUTH_HEADER), never in the
 * URL - a compliance win over urchin's v2 query key. A live call without it returns 401
 * "Invalid API Key". The header NAME ("key") was confirmed live 2026-07-21 and can still be
 * overridden with the SERAPH_AUTH_HEADER env var if the spelling ever changes.
 *
 * Keys live ONLY in this Worker (SERAPH_KEY secret for the owner, else the caller's KV slot);
 * redirects are disabled so the keyed request is never replayed off-origin, and the key never
 * appears in URLs, logs, responses, or client config.
 *
 * Gating (all must hold before any Seraph traffic):
 *   - STATS_TOKEN configured AND matched (a personal key must not serve strangers)
 *   - X-BWQOL-Seraph: 1 opt-in header (client sends it only for identity-confirmed tasks)
 *   - a key source available (SERAPH_KEY secret, or STATS_KV holding the caller's key slot)
 *
 * NO PERSISTENT CACHE (§7d): unlike urchin.js's shared 6 h/24 h per-UUID KV cache, Seraph is
 * single-owner with no cross-user fanout, so tag results are NEVER written to KV or caches.default.
 * Lookups are per-encounter and user-driven; the only reuse is an ephemeral per-isolate in-flight
 * dedupe that collapses concurrent lookups of the same UUID within a single request. A cleared key
 * therefore leaves nothing displayable anywhere by construction.
 *
 * Rate limit (provisional, live headers): x-ratelimit-limit:120 / x-ratelimit-reset:60 (treated as
 * 120 requests / 60 s per IP). Every response's x-ratelimit-remaining / x-ratelimit-reset is read;
 * remaining==0 or an HTTP 429 arms an isolate-local backoff until reset (persisted best-effort to KV
 * when STATS_KV is bound - a tiny protective flag, not a fanout cache). Treat 120/60 as provisional.
 */

const SERAPH_BASE_DEFAULT = "https://api.seraph.si";
// Confirmed live 2026-07-21 against api.seraph.si/<uuid>/blacklist: the key travels in the "key"
// header. Still overridable with the SERAPH_AUTH_HEADER env var if the spelling ever changes.
const SERAPH_AUTH_HEADER_DEFAULT = "key";

const RL_LIMIT = 120; // provisional: requests per window
const RL_WINDOW_MS = 60 * 1000; // provisional: window length (x-ratelimit-reset seconds)
const BACKOFF_MS = 60 * 1000; // fallback backoff after a 429 with no usable reset header
const DISABLED_RETRY_MS = 60 * 60 * 1000; // after a 401/403 (bad/locked key)
const LOOKUP_CONCURRENCY = 4; // bounded per-UUID fan-out (well under RL_LIMIT for a full lobby)

const UUID_RE = /^[0-9a-f]{32}$/;

// Per-isolate in-flight dedupe: uuid -> { identity, promise }. The ONLY reuse (§7d: no
// persistent cache); `identity` is the requester whose key executes the upstream call.
const inflight = new Map();

/**
 * Per-identity isolate state (bounded by the token-list length, <= ~5). Success resets and
 * key-change resets apply ONLY to the identity that earned them.
 */
const isoState = new Map(); // identity -> mutable record below
function iso(identity) {
  let s = isoState.get(identity);
  if (!s) {
    s = {
      keyRejectedAt: 0, // time-bounded: re-consult after DISABLED_RETRY_MS
      backoffUntil: 0, // armed from x-ratelimit / 429; KV write is best-effort cross-isolate
      failStreak: 0,
      breakerUntil: 0,
      lastFailAt: 0,
    };
    isoState.set(identity, s);
  }
  return s;
}

const cfgKeyName = (identity) => `seraph:cfg:key:${identity}`;
const cfgBackoffName = (identity) => `seraph:cfg:backoff:${identity}`;
const cfgDisabledName = (identity) => `seraph:cfg:disabled:${identity}`;

function seraphBase(env) {
  return (env && env.SERAPH_BASE) || SERAPH_BASE_DEFAULT;
}

function authHeader(env) {
  return (env && env.SERAPH_AUTH_HEADER) || SERAPH_AUTH_HEADER_DEFAULT;
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

/** Unix ms from either a finite number or an ISO-8601 string; null otherwise. */
function toMs(v) {
  if (Number.isFinite(v)) return v;
  if (typeof v === "string" && v) {
    const p = Date.parse(v.endsWith("Z") || /[+-]\d\d:\d\d$/.test(v) ? v : v + "Z");
    if (Number.isFinite(p)) return p;
  }
  return null;
}

/** True when a Seraph list sub-object is present and explicitly flagged tagged. */
function listed(x) {
  return x && typeof x === "object" && x.tagged === true;
}

/**
 * Map a Seraph `data` object to our displayed tag list. Unlike urchin (blacklist only), Seraph
 * surfaces four requested signals, each mapped to one tag keyed by its list `kind`:
 *   - blacklist -> cheater accusation (report_type carried; `verified` bumps confidence)
 *   - safelist  -> "legit"/whitelisted (the false-flag signal; NOT a cheater type)
 *   - bot       -> known bot account
 *   - annoy     -> annoylist (low-severity nuisance)
 * Only the OWNER's own client ever sees these (single-owner, §7d) - this is data use, not fanout, so
 * the full requested surface is preserved. Everything else (member/name_change) is dropped. Returns
 * [] when the player is on none of the four lists.
 */
export function mapTags(data, nowMs) {
  if (!data || typeof data !== "object") return [];
  const out = [];
  const bl = data.blacklist;
  if (listed(bl)) {
    const rt = sanitizeText(String(bl.report_type || ""), 40).toLowerCase();
    out.push({
      kind: "blacklist",
      subtype: rt, // e.g. "cheater"/"sniper"; "" when unspecified
      reason: sanitizeText(String(bl.reason || bl.tooltip || ""), 120),
      addedOn: toMs(bl.timestamp) || 0,
      verified: bl.verified === true,
    });
  }
  const sl = data.safelist;
  if (listed(sl)) {
    out.push({
      kind: "safelist",
      subtype: "",
      reason: sanitizeText(String(sl.tooltip || ""), 120),
      addedOn: toMs(sl.time_added) || 0,
      verified: false,
    });
  }
  if (listed(data.bot)) {
    out.push({ kind: "bot", subtype: "", reason: sanitizeText(String(data.bot.tooltip || ""), 120), addedOn: 0, verified: false });
  }
  if (listed(data.annoylist)) {
    out.push({ kind: "annoy", subtype: "", reason: sanitizeText(String(data.annoylist.tooltip || ""), 120), addedOn: 0, verified: false });
  }
  return out;
}

/** Extract {threatLevel, encounters} from statistics for the detail panel; either may be null. */
export function mapStatistics(data) {
  const s = data && typeof data === "object" ? data.statistics : null;
  if (!s || typeof s !== "object") return { threatLevel: null, encounters: null };
  const tl = Number(s.threat_level);
  const en = Number(s.encounters);
  return {
    threatLevel: Number.isFinite(tl) ? tl : null,
    encounters: Number.isFinite(en) ? en : null,
  };
}

// ---- gating ---------------------------------------------------------------

/**
 * Authenticated identity + explicit opt-in for Seraph DATA routes (capability is separate).
 * Pure function of the route-level auth result: it never re-derives authentication, and an
 * open worker (null identity) stays fail-closed exactly as the unset-secret case always was.
 */
export function seraphAllowed(auth, request) {
  return Boolean(auth && auth.identity && request.headers.get("X-BWQOL-Seraph") === "1");
}

/**
 * Capability: a key source must exist (SERAPH_KEY secret, or STATS_KV that may hold the configured
 * key) or every data route makes zero Seraph calls - an authenticated opted-in owner then gets
 * resolved "unavailable" metadata rather than a misleading authentication error.
 */
export function seraphCapable(env) {
  return Boolean(env && (env.SERAPH_KEY || env.STATS_KV));
}

/** Gate for the set-key route: authenticated identity + KV only (no opt-in, no master dependency). */
export function keyRouteAllowed(auth, env) {
  return Boolean(auth && auth.identity && env && env.STATS_KV);
}

/** The calling identity's key: the SERAPH_KEY secret for the OWNER only, else its KV slot. */
async function activeKey(env, auth) {
  if (!auth || !auth.identity) return { key: null, fromSecret: false };
  if (auth.isOwner && env && env.SERAPH_KEY) return { key: env.SERAPH_KEY, fromSecret: true };
  if (env && env.STATS_KV) {
    try {
      const k = await env.STATS_KV.get(cfgKeyName(auth.identity));
      if (k) return { key: k, fromSecret: false };
    } catch (_) { /* ignore */ }
  }
  return { key: null, fromSecret: false };
}

// ---- backoff / disabled state ---------------------------------------------
// No fixed KV budget window (urchin has none either) - the live x-ratelimit headers drive backoff
// directly. Backoff/disabled are isolate-local first; a tiny protective flag is mirrored to KV when
// bound (single-owner config, NOT the per-user fanout cache §7d forbids).

async function isBlocked(env, nowMs, auth) {
  const st = iso(auth.identity);
  if (st.keyRejectedAt && nowMs - st.keyRejectedAt < DISABLED_RETRY_MS) return true;
  if (nowMs < st.backoffUntil) return true;
  if (!(env && env.STATS_KV)) return false;
  try {
    const [backoff, disabled] = await Promise.all([
      env.STATS_KV.get(cfgBackoffName(auth.identity)),
      env.STATS_KV.get(cfgDisabledName(auth.identity)),
    ]);
    if (backoff && Number(backoff) > nowMs) return true;
    if (disabled && nowMs - Number(disabled) < DISABLED_RETRY_MS) return true;
  } catch (_) {
    // Cannot read the shared block state -> fail closed for one backoff interval.
    st.backoffUntil = Math.max(st.backoffUntil, nowMs + BACKOFF_MS);
    return true;
  }
  return false;
}

/** Arm the calling identity's backoff until `untilMs` (from x-ratelimit-reset, else a fallback window). */
function noteBackoff(env, ctx, untilMs, auth) {
  const st = iso(auth.identity);
  st.backoffUntil = Math.max(st.backoffUntil, untilMs);
  if (env && env.STATS_KV) {
    const put = env.STATS_KV.put(cfgBackoffName(auth.identity), String(untilMs), {
      expirationTtl: Math.ceil((RL_WINDOW_MS * 2) / 1000),
    });
    if (ctx) ctx.waitUntil(put.catch(() => {}));
  }
}

function noteKeyRejected(env, ctx, nowMs, auth) {
  const st = iso(auth.identity);
  if (!st.keyRejectedAt) console.log("SERAPH_KEY_REJECTED");
  st.keyRejectedAt = nowMs;
  if (env && env.STATS_KV) {
    const put = env.STATS_KV.put(cfgDisabledName(auth.identity), String(nowMs), { expirationTtl: 7200 });
    if (ctx) ctx.waitUntil(put.catch(() => {}));
  }
}

// ---- upstream-failure circuit breaker --------------------------------------

/**
 * A 429 arms a backoff and a 401/403 arms DISABLED_RETRY_MS, but a plain upstream failure
 * (5xx / timeout / unparseable body) arms nothing and - §7d - is never cached, so during a Seraph
 * outage every lobby re-issues one keyed GET per player, every 60 s, forever. This bounds that.
 *
 * Structurally identical to urchin.js's shipped breaker: two isolate-local numbers plus a
 * timestamp, zero KV reads and zero KV writes (§7d untouched - no result data is stored anywhere).
 *
 * It fails OPEN by construction: state only ever suppresses calls for at most FAIL_COOLDOWN_MS, any
 * successful round trip clears it, and an unset value never compares true. A tripped lookup returns
 * the ordinary transient null (omitted from the map, client keeps PENDING and retries on its
 * existing cadence) rather than "unavailable", which the client treats as resolved FOREVER - an
 * outage must never pin a player as un-checkable.
 *
 * 3 consecutive failures: one blip must not stop anything. 60 s: long enough to stop the retry
 * storm, short enough to recover in-game unnoticed.
 */
const FAIL_STREAK_TRIP = 3;
const FAIL_COOLDOWN_MS = 60 * 1000;

function noteFail(nowMs, st) {
  // Decay: "3 consecutive failures" is only evidence of an outage when they are close together.
  // Without this, three unrelated blips hours apart trip a 60 s suppression for no reason.
  if (st.lastFailAt && nowMs - st.lastFailAt > FAIL_COOLDOWN_MS) st.failStreak = 0;
  st.lastFailAt = nowMs;
  if (++st.failStreak >= FAIL_STREAK_TRIP) {
    st.failStreak = 0;
    st.breakerUntil = nowMs + FAIL_COOLDOWN_MS;
    console.log("SERAPH_BREAKER_TRIPPED cooldown_ms=" + FAIL_COOLDOWN_MS);
  }
}

/** Any completed round trip proves the upstream is answering, so it clears the calling
 *  identity's streak. */
function noteOk(st) {
  st.failStreak = 0;
  st.breakerUntil = 0;
  st.lastFailAt = 0;
}

// ---- Seraph calls ---------------------------------------------------------

/** Parse the rate-limit headers into { remaining, resetMs } (either may be null). */
function readRateLimit(res, nowMs) {
  const remaining = Number(res.headers.get("x-ratelimit-remaining"));
  const resetSec = Number(res.headers.get("x-ratelimit-reset"));
  return {
    remaining: Number.isFinite(remaining) ? remaining : null,
    // x-ratelimit-reset is documented as seconds-until-reset; fall back to the provisional window.
    resetMs: Number.isFinite(resetSec) ? nowMs + resetSec * 1000 : nowMs + RL_WINDOW_MS,
  };
}

/**
 * GET /{uuid}/blacklist with the key in a header. Returns a tagged status result. workerd only
 * supports "follow"/"manual"; "manual" + explicit 3xx failure means the keyed request is never
 * replayed off-origin. The upstream URL is never logged.
 */
async function seraphFetch(env, uuid, nowMs, auth) {
  const { key } = await activeKey(env, auth);
  if (!key) return { status: "nokey" };
  let res;
  try {
    res = await fetch(`${seraphBase(env)}/${uuid}/blacklist`, {
      method: "GET",
      headers: { [authHeader(env)]: key, accept: "application/json" },
      redirect: "manual",
    });
  } catch (_) {
    return { status: "error" };
  }
  const rl = readRateLimit(res, nowMs);
  if (res.status >= 300 && res.status < 400) {
    try { await res.body?.cancel(); } catch (_) {}
    return { status: "error", rl };
  }
  if (res.status === 429) return { status: "ratelimited", rl };
  if (res.status === 401 || res.status === 403) return { status: "rejected", rl };
  if (res.status === 404) return { status: "notfound", rl };
  if (!res.ok) {
    try { await res.body?.cancel(); } catch (_) {}
    return { status: "error", rl };
  }
  let json;
  try { json = await res.json(); } catch (_) { return { status: "error", rl }; }
  // Seraph error object: {success:false, code, msTime, cause, documentation, extra:[...]}.
  if (json && json.success === false) {
    const cause = typeof json.cause === "string" ? json.cause : "";
    if (/invalid api key|unauthor/i.test(cause)) return { status: "rejected", rl };
    return { status: "error", rl };
  }
  // Documented success payload nests the record under `data`; accept a bare object too.
  const data = json && typeof json === "object" && json.data ? json.data : json;
  return { status: "ok", data, rl };
}

/** Feed a response's rate-limit signal into the calling identity's backoff (proactive when
 *  remaining hits 0). */
function absorbRateLimit(env, ctx, rl, nowMs, auth) {
  if (rl && rl.remaining != null && rl.remaining <= 0 && rl.resetMs) {
    noteBackoff(env, ctx, rl.resetMs, auth);
  }
}

/**
 * Resolve one canonical UUID with isolate in-flight dedupe. Returns { identity, promise }
 * where `identity` is the EXECUTING requester - the caller compares it against its own to
 * apply per-requester settlement (a piggybacked failure must not inherit the executing key's
 * "unavailable" shape). All state notes inside the promise attribute to the executing
 * identity, which created it.
 */
function lookupOne(uuid, env, ctx, nowMs, auth) {
  const existing = inflight.get(uuid);
  if (existing) return existing;
  const st = iso(auth.identity);
  // Tripped breaker: report the same transient failure the callers already handle, without
  // spending a subrequest. Not counted as a failure - it never reached the upstream.
  if (Date.now() < st.breakerUntil) return { identity: auth.identity, promise: Promise.resolve(null) };
  const entry = {
    identity: auth.identity,
    promise: (async () => {
      const r = await seraphFetch(env, uuid, nowMs, auth);
      if (r.status === "ok") {
        noteOk(st);
        absorbRateLimit(env, ctx, r.rl, nowMs, auth);
        const stats = mapStatistics(r.data);
        return { state: "ok", tags: mapTags(r.data, nowMs), threatLevel: stats.threatLevel, encounters: stats.encounters };
      }
      if (r.status === "notfound") {
        noteOk(st);
        absorbRateLimit(env, ctx, r.rl, nowMs, auth);
        return { state: "notfound", tags: [] };
      }
      if (r.status === "ratelimited") {
        noteBackoff(env, ctx, (r.rl && r.rl.resetMs) || nowMs + BACKOFF_MS, auth);
        return { state: "unavailable", tags: [] };
      }
      if (r.status === "rejected") {
        noteKeyRejected(env, ctx, nowMs, auth);
        return { state: "unavailable", tags: [] };
      }
      if (r.status === "nokey") return { state: "unavailable", tags: [] };
      // Transient error: omit so the client may retry later (no cache to fall back on), and feed the
      // outage breaker so a sustained failure stops re-issuing one GET per player per lobby.
      noteFail(nowMs, st);
      return null;
    })().finally(() => { if (inflight.get(uuid) === entry) inflight.delete(uuid); }),
  };
  inflight.set(uuid, entry);
  return entry;
}

/**
 * Resolve tags for canonical UUIDs. Returns Map<uuid, result> where result is
 * { state: "ok"|"notfound"|"unavailable", tags: [...] } - "ok"/"notfound" mean concluded
 * (seraphChecked), "unavailable" means no data could be obtained now (seraphUnavailable). Missing
 * map entries mean transient failure (client may retry later). NO cache: every eligible UUID is a
 * fresh per-encounter lookup (§7d), fanned out with bounded concurrency under the rate limit.
 */
export async function tagsForUuids(rawUuids, env, ctx, auth) {
  const nowMs = Date.now();
  const out = new Map();
  const uuids = [];
  for (const raw of rawUuids) {
    const u = normalizeUuid(raw);
    if (u && !uuids.includes(u)) uuids.push(u);
  }
  if (uuids.length === 0) return out;

  const { key } = await activeKey(env, auth);
  if (!key) {
    // No key -> Seraph is off entirely: resolved unavailable (no cache to leak from anyway).
    for (const u of uuids) out.set(u, { state: "unavailable", tags: [] });
    return out;
  }
  if (await isBlocked(env, nowMs, auth)) {
    for (const u of uuids) out.set(u, { state: "unavailable", tags: [] });
    return out;
  }
  const st = iso(auth.identity);

  // Bounded fan-out: one keyed GET per UUID, LOOKUP_CONCURRENCY in flight at a time.
  let idx = 0;
  const runNext = async () => {
    while (idx < uuids.length) {
      const u = uuids[idx++];
      // The CALLER's backoff armed mid-batch (429 / remaining==0) short-circuits the rest
      // as unavailable - another identity's state never short-circuits this batch.
      if (st.backoffUntil > Date.now() || (st.keyRejectedAt && Date.now() - st.keyRejectedAt < DISABLED_RETRY_MS)) {
        out.set(u, { state: "unavailable", tags: [] });
        continue;
      }
      const held = lookupOne(u, env, ctx, nowMs, auth);
      const r = await held.promise;
      if (!r) continue;
      // Per-requester settlement (D4): success-class results are shared facts; a failure-class
      // result ("unavailable") belongs to the EXECUTING identity's key, so a piggybacking
      // requester gets full omission instead (Seraph has no stale cache) - retryable, never
      // pinned resolved by someone else's failure.
      if (held.identity === auth.identity || r.state === "ok" || r.state === "notfound") out.set(u, r);
    }
  };
  const n = Math.min(LOOKUP_CONCURRENCY, uuids.length);
  await Promise.all(Array.from({ length: n }, runNext));
  return out;
}

/** Shape a per-player result into response fields (seraph/seraphChecked/seraphUnavailable). */
export function resultFields(result, uuid) {
  if (!result) return {};
  const fields = {};
  // Unambiguous provenance for the client merge: the canonical UUID this result belongs to.
  if (uuid) fields.seraphUuid = uuid;
  if (result.state === "ok" || result.state === "notfound") fields.seraphChecked = true;
  if (result.state === "notfound") fields.seraphNotFound = true;
  if (result.state === "unavailable") fields.seraphUnavailable = true;
  const hasTags = Array.isArray(result.tags) && result.tags.length > 0;
  const hasStats = result.threatLevel != null || result.encounters != null;
  if (hasTags || hasStats) {
    const seraph = {};
    if (hasTags) {
      seraph.tags = result.tags.map(({ kind, subtype, reason, addedOn, verified }) => ({
        kind, ...(subtype ? { subtype } : {}), reason, addedOn, ...(verified ? { verified: true } : {}),
      }));
    }
    if (result.threatLevel != null) seraph.threatLevel = result.threatLevel;
    if (result.encounters != null) seraph.encounters = result.encounters;
    fields.seraph = seraph;
  }
  return fields;
}

/** POST /seraph/key: {key: "..."} sets, {key: null} clears - the CALLER's slot only, so a
 *  cross-user overwrite is impossible by construction. Never echoes the key. */
export async function handleKeySet(request, env, ctx, auth) {
  if (!env || !env.STATS_KV) {
    return json({ success: false, error: "kv_required" }, 503);
  }
  if (!keyRouteAllowed(auth, env)) {
    return json({ success: false, error: "unauthorized" }, 403);
  }
  // The SERAPH_KEY secret manages the OWNER's slot only; other identities keep KV slots.
  if (auth.isOwner && env.SERAPH_KEY) {
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
    await env.STATS_KV.delete(cfgBackoffName(auth.identity));
    await env.STATS_KV.delete(cfgDisabledName(auth.identity));
    if (key === null) await env.STATS_KV.delete(cfgKeyName(auth.identity));
    else await env.STATS_KV.put(cfgKeyName(auth.identity), key);
  } catch (_) {
    return json({ success: false, error: "kv_write_failed" }, 503);
  }
  // A key change is an explicit "try again now" - for the CALLING identity only.
  const st = iso(auth.identity);
  st.keyRejectedAt = 0;
  st.backoffUntil = 0;
  st.failStreak = 0;
  st.breakerUntil = 0;
  st.lastFailAt = 0;
  return json({ success: true });
}

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status: status || 200,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
