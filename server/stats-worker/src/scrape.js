/**
 * Scraping layer: streaming early-abort fetch of hypixel.net/player HTML, single + batch resolution,
 * and a politeness pool that respects hypixel's measured per-IP origin rate limit.
 *
 * MEASURED CONSTRAINT (2026-06-26, against this worker's egress IP): hypixel throttles cache-MISS
 * (origin) scrapes to ~1.7 req/s per IP with NO burst allowance — clean at 1.54/s, ~50% HTTP 429 at
 * 2.86/s. There is no lighter endpoint; the full ~258 KB page (gzips to ~19 KB) is the only source.
 * So the batch can only scrape misses at ~1.6/s; it STREAMS results (cache hits first, then scrapes
 * as they land) so the client can render progressively instead of waiting for the slowest player.
 */

import { parseBedwarsFromHtml } from "./bedwars-parse.js";
import { parseProfile } from "./profile-parse.js";
import {
  readCached, writeCached, readBlocked, writeBlocked, NEG_TTL_SEC, NICKED_TTL_SEC, NICK_404_TTL_SEC,
} from "./cache.js";
import { tagsForUuids, resultFields } from "./urchin.js";
import { tagsForUuids as seraphTagsForUuids, resultFields as seraphResultFields } from "./seraph.js";
import { shmeadoScrapeAndCache } from "./shmeado.js";

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

// Markers of an actual Cloudflare interstitial. `challenge-platform` is deliberately NOT here:
// that token is the /cdn-cgi/challenge-platform/ beacon script, which Cloudflare injects into
// EVERY proxied response including successful player pages, so matching it made the verdict a
// pure function of the <50 KB size gate rather than of the page's content.
const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|attention required/i;

// XenForo's error page marker. Its presence on a 404 proves the forum application itself answered
// "no such member" (a nick); its absence means something in front of the forum produced the 404.
const FORUM_ERROR_TEMPLATE = 'data-template="error"';

// Header + full Bedwars block live in the first ~21% (~55 KB decompressed) of the ~258 KB page.
const NEEDLE = "stats-content-bedwars";
const BEDWARS_CUSHION = 22_000; // bytes to keep reading past the needle to capture the whole block
const MAX_PREFIX_BYTES = 90_000; // hard stop; also fully contains any <50 KB challenge page

// Cloudflare's origin-error family: the edge answered us, but hypixel's own server did not answer
// the edge (520 unknown, 521 down, 522 connect timeout, 523 unreachable, 524 read timeout,
// 525/526 SSL, 527 railgun). Distinct from a challenge/403, which is the edge refusing US.
const ORIGIN_DOWN_MIN = 520;
const ORIGIN_DOWN_MAX = 527;

// Bound on one player-page fetch + prefix read. Measured healthy: ~0.3 s for the full 264 KB page
// (the worker's own egress probe reports ~85 ms), so this is ~40x headroom. It sits deliberately
// BELOW the ~20 s Cloudflare itself takes to give up and emit a 522: without a bound, every lookup
// during a hypixel outage held an origin-gate slot for that full ~20 s. Because it fires first, an
// abort we armed is classified as origin-down too - otherwise the bound would hide the very 522
// that trips the breaker.
const FETCH_TIMEOUT_MS = 12_000;

// Politeness toward hypixel — pinned to the measured ~1.7/s per-IP origin limit (with margin).
const BATCH_CFG = {
  concurrency: 2, // gate below dominates; 2 lets a scrape overlap the next start's latency
  maxAttempts: 4, // 429 backoff itself now lives in the shared origin gate below
};
const MAX_BATCH = 24;

// Attempts for the single route's counter scrape (the batch pool uses BATCH_CFG.maxAttempts).
const SINGLE_MAX_ATTEMPTS = 3;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Origin gate lanes. HIGH = a human is waiting on it; LOW = background lobby work. */
export const LANE_HIGH = 0;
export const LANE_LOW = 1;

const SPACING_MS = 620; // ~1.6/s — the measured ceiling; never tightened
const ELEVATED_SPACING_MS = 1300; // collapse to ~today's proven-safe rate on a 429
const ELEVATION_MS = 60_000; // how long a 429 keeps spacing elevated before decaying
const ORIGIN_BACKOFF_BASE_MS = 1200; // per-attempt pause after a 429
const HIGH_STREAK_MAX = 3; // fairness floor: the low lane gets >= 1 slot in 4
const STALE_WAITER_MS = 60_000; // a waiter older than this is treated as abandoned
const PICK_QUANTUM_MS = 25; // re-check interval while it is not my turn

/**
 * Shared origin gate. EVERY hypixel origin start goes through it - the batch counter pool, the
 * single route's counter scrape and the diagnostic probe - so a /bw lookup can no longer stack on
 * top of a running batch and push the isolate past the measured ~1.7 req/s per-IP limit. A 429 on
 * any path throttles all of them.
 *
 * Two lanes, one timeline. Nothing is ever RESERVED: `lastStart` is a past fact, not a future
 * claim, so each slot's winner is chosen at release time from whoever is waiting. That is what lets
 * a HIGH arrival get ahead of already-queued LOW work, which the old synchronous claim could not do
 * (its waiters were asleep holding earlier timestamps that nothing could revoke).
 *
 * Rate invariant: `lastStart` is assigned in exactly ONE place, to `now`, and only when
 * `now >= lastStart + spacingAt(now)`. So any two consecutive grants are at least the current
 * spacing apart for every lane mix - priority chooses WHICH waiter is served, never HOW MANY.
 *
 * Why polling and not one shared timer: in workerd a timer belongs to the I/O context that
 * scheduled it, so a single pump scheduled by request A on behalf of request B's waiter dies when A
 * ends, hanging B. Polling keeps every sleep inside its own live request context. The cost is up to
 * PICK_QUANTUM_MS of slack per contended grant, which can only make the gate SLOWER than the
 * spacing, never faster, so it cannot violate the measured constraint.
 *
 * HIGH_STREAK_MAX = 3 guarantees the low lane at least 1 slot in 4. `highStreak` counts consecutive
 * grants taken WHILE A LOW WAITER WAS PRESENT (hence the reset when there is none), so an
 * uncontended high run cannot cause a spurious later deferral. It costs a high waiter at most one
 * slot of deferral: worst case 2 slots (~1.24 s), typically 1 (~0.62 s).
 *
 * 429 elevation now applies to both lanes AND retroactively to already-queued waiters, because
 * spacingAt/backoffUntil are read at grant time instead of at claim time. Under the old synchronous
 * claim a waiter that claimed before the 429 kept its pre-429 timestamp; this is strictly safer.
 *
 * Concurrency 2 stays. A batch contributes at most 2 waiters, and since nothing is reserved a high
 * arrival's wait is bounded by one spacing plus at most one deferral REGARDLESS of batch size. The
 * second worker exists only so a page fetch slower than the spacing does not idle the gate.
 *
 * Abandoned waiters: if a request context is torn down mid-sleep the coroutine is discarded and
 * `finally` may not run, leaving a phantom waiter. A phantom in the high lane would otherwise be
 * picked forever and never grant itself, blocking the low lane permanently. So oldest() skips
 * waiters older than STALE_WAITER_MS and pickNext falls back to oldest-of-all when only stale
 * entries remain: deprioritized, never dropped, never hung. 60 s is ~2x the longest legitimate wait
 * (24 names x 1300 ms elevated plus one backoff pause ~= 33 s).
 *
 * Cold start: `lastStart = 0` means the first claim after an idle period is granted instantly. That
 * single-request burst exists today, is what makes an idle-lobby /bw fast, and is deliberately NOT
 * a since-boot rule. It is not a token bucket - no burst allowance accumulates.
 *
 * PER-ISOLATE (platform limit): Workers offers no cross-isolate coordination short of a Durable
 * Object, so two isolates can still overlap. Accepted for single-owner traffic — the amplification
 * being removed is the within-isolate kind.
 */
const ORIGIN_GATE = {
  lastStart: 0,
  backoffUntil: 0,
  elevatedUntil: 0,
  waiters: [], // { lane, seq, at }
  seq: 0,
  highStreak: 0,
};

function spacingAt(now) {
  return now < ORIGIN_GATE.elevatedUntil ? ELEVATED_SPACING_MS : SPACING_MS;
}

/** Oldest live waiter in `lane` (FIFO by seq), skipping abandoned ones. */
function oldest(lane, now) {
  let best = null;
  for (const w of ORIGIN_GATE.waiters) {
    if (w.lane !== lane || now - w.at > STALE_WAITER_MS) continue;
    if (!best || w.seq < best.seq) best = w;
  }
  return best;
}

function oldestOfAll() {
  let best = null;
  for (const w of ORIGIN_GATE.waiters) if (!best || w.seq < best.seq) best = w;
  return best;
}

/** Who gets the next slot: strict priority, bounded by the low lane's fairness floor. */
function pickNext(now) {
  const hi = oldest(LANE_HIGH, now);
  const lo = oldest(LANE_LOW, now);
  if (lo && (!hi || ORIGIN_GATE.highStreak >= HIGH_STREAK_MAX)) return lo;
  if (hi) return hi;
  return lo || oldestOfAll(); // only abandoned waiters left: serve one, never hang
}

/** Wait for this lane's turn on the shared timeline. Returns once the origin start is granted. */
export async function claimOrigin(lane) {
  const w = { lane, seq: ++ORIGIN_GATE.seq, at: Date.now() };
  ORIGIN_GATE.waiters.push(w);
  try {
    while (true) {
      const now = Date.now();
      const earliest = Math.max(ORIGIN_GATE.lastStart + spacingAt(now), ORIGIN_GATE.backoffUntil);
      if (now >= earliest && pickNext(now) === w) {
        ORIGIN_GATE.lastStart = now; // the ONLY assignment: rate invariant lives here
        if (lane === LANE_HIGH) {
          ORIGIN_GATE.highStreak = oldest(LANE_LOW, now) ? ORIGIN_GATE.highStreak + 1 : 0;
        } else {
          ORIGIN_GATE.highStreak = 0;
        }
        return;
      }
      await sleep(now >= earliest ? PICK_QUANTUM_MS : earliest - now);
    }
  } finally {
    const i = ORIGIN_GATE.waiters.indexOf(w);
    if (i >= 0) ORIGIN_GATE.waiters.splice(i, 1);
  }
}

/** Feed a 429 back into the gate: pause every path, then keep spacing elevated for a bounded window. */
export function noteOrigin429(attempt) {
  const now = Date.now();
  ORIGIN_GATE.backoffUntil = Math.max(
    ORIGIN_GATE.backoffUntil,
    now + ORIGIN_BACKOFF_BASE_MS * Math.max(1, attempt)
  );
  ORIGIN_GATE.elevatedUntil = now + ELEVATION_MS; // bounded: decays back to per-claim spacing
}

/** Read just enough of the response body to cover the header + Bedwars block, then abort the rest. */
async function readHtmlPrefix(response) {
  if (!response.body) return await response.text();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let html = "";
  let bytes = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      html += decoder.decode(value, { stream: true });
      const idx = html.indexOf(NEEDLE);
      if ((idx >= 0 && html.length - idx >= BEDWARS_CUSHION) || bytes >= MAX_PREFIX_BYTES) break;
    }
  } finally {
    try { await reader.cancel(); } catch (_) { /* ignore */ }
  }
  return html;
}

function errBody(player, error, httpStatus) {
  const b = { success: false, state: "ERROR", displayName: player, error };
  if (httpStatus != null) b.httpStatus = httpStatus;
  return b;
}

/**
 * Fetch + stream-read a player page, bounded by FETCH_TIMEOUT_MS.
 * Returns { ok, html } or { ok:false, body, retry429?, originDown? }.
 *
 * `originDown` marks "hypixel itself is not answering" (as opposed to a block aimed at this
 * worker). It is an internal control flag beside `retry429`, never a wire field: the body keeps
 * its precise http_<code> so the failure stays diagnosable from a client response.
 */
export async function scrapePlayerHtml(player, env) {
  const base = (env && env.HYPIXEL_BASE) || "https://hypixel.net";
  const target = `${base}/player/${encodeURIComponent(player)}`;
  const aborter = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; aborter.abort(); }, FETCH_TIMEOUT_MS);
  try {
    let response;
    try {
      response = await fetch(target, {
        method: "GET",
        headers: {
          "User-Agent": BROWSER_UA,
          Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "Accept-Language": "en-US,en;q=0.9",
        },
        redirect: "follow",
        signal: aborter.signal,
      });
    } catch (e) {
      if (timedOut) return { ok: false, originDown: true, body: errBody(player, "origin_timeout") };
      return { ok: false, body: errBody(player, String(e && e.message ? e.message : e)) };
    }

    // Rate-limited by hypixel: signal the pool to back off and retry.
    if (response.status === 429) {
      try { await response.body?.cancel(); } catch (_) {}
      return { ok: false, retry429: true, body: errBody(player, "http_429", 429) };
    }

    let html;
    try { html = await readHtmlPrefix(response); }
    catch (e) {
      // The bound covers the streaming read too: an origin that accepts the connection and then
      // stalls mid-body is down in every way that matters to us.
      if (timedOut) return { ok: false, originDown: true, body: errBody(player, "origin_timeout") };
      return { ok: false, body: errBody(player, "stream_" + String(e && e.message ? e.message : e)) };
    }

    // A 404 from the forum APPLICATION is hypixel answering: there is no member by that name. That
    // is precisely what a /nick looks like from the forum's side, so it is the NICKED verdict - the
    // same one the shmeado source already returns for these names.
    //
    // The XenForo error template is what makes it an answer rather than a failure. A 404 without it
    // was not produced by the forum at all (Cloudflare, a proxy, a routing change), so it is
    // infrastructure trouble and stays a retryable ERROR - never a confident "this player is nicked".
    // Measured: the marker sits at byte ~92 of the ~42 KB 404 page (so always inside the prefix read)
    // and appears on no successful player page.
    //
    // This must be decided BEFORE the challenge heuristic below. The 404 page slips under the
    // <50 KB size gate, so while `challenge-platform` was still a challenge marker every nicked
    // player in a lobby was read as `blocked_by_cloudflare`: it tripped the global origin breaker
    // and rendered as a blank tab cell instead of [Nicked].
    if (response.status === 404) {
      if (html.includes(FORUM_ERROR_TEMPLATE)) {
        return { ok: false, body: { success: false, state: "NICKED", displayName: player, httpStatus: 404 } };
      }
      return { ok: false, body: errBody(player, "http_404", 404) };
    }

    // Also decided by status BEFORE the content heuristic, for the same reason the 404 is: a
    // Cloudflare origin-error page is short enough to slip under the <50 KB gate, and calling a
    // hypixel-side outage "blocked_by_cloudflare" would misreport who is failing. Both trip the
    // breaker, so the fallback engages either way - this only keeps the verdict honest.
    if (response.status >= ORIGIN_DOWN_MIN && response.status <= ORIGIN_DOWN_MAX) {
      return {
        ok: false,
        originDown: true,
        body: errBody(player, "http_" + response.status, response.status),
      };
    }

    if (response.status === 403 || (html.length < 50_000 && CHALLENGE_RE.test(html))) {
      return { ok: false, body: errBody(player, "blocked_by_cloudflare", response.status) };
    }
    if (response.status < 200 || response.status >= 300) {
      return { ok: false, body: errBody(player, "http_" + response.status, response.status) };
    }
    return { ok: true, html };
  } finally {
    clearTimeout(timer);
  }
}

/** Scrape one player from the request's pinned source, parse, and cache. Returns { body, retry429? }. */
async function scrapeAndCache(player, env, ctx, source) {
  // The source was pinned ONCE at request entry (getBedwars / streamBedwarsBatch read the
  // breaker there), so a 429-retry loop or a batch's later misses can never flip sources
  // mid-request - one source per HTTP request, never both. The shmeado path never feeds the
  // origin gate, never trips the breaker, and never sets retry429.
  if (source === "shmeado") {
    return await shmeadoScrapeAndCache(player, env, ctx);
  }
  const scraped = await scrapePlayerHtml(player, env);
  if (!scraped.ok) {
    // Two shapes of "hypixel is not answering", one breaker:
    //   blocked_by_cloudflare - the EDGE is refusing this worker (challenge page / 403)
    //   originDown            - the edge is fine, hypixel's own origin is not (520-527 / timeout)
    // Either way every further hypixel scrape this window is doomed, which is exactly the
    // condition that pins the next request to shmeado.
    const trippedBreaker = scraped.originDown === true
      || (scraped.body && scraped.body.error === "blocked_by_cloudflare");
    if (trippedBreaker) writeBlocked(env, ctx);
    // 429s are NOT negatively cached: the pool retries them, and a cached terminal 429
    // would poison the very lookups the backoff is about to make succeed.
    // A breaker-tripping failure is not cached either, for a sharper reason: readCached runs
    // BEFORE the source is pinned (see getBedwars / streamBedwarsBatch), so a 90 s negative
    // outlives nothing but its own usefulness - it would be served in preference to the
    // fallback for most of the breaker's 120 s life, leaving the flag routing around hypixel
    // while the cache kept handing back hypixel's error. Same rationale as the uncached
    // transport failures in shmeado.js.
    // A 404-sourced NICKED is not a failure, so it is cached rather than retried - but on the
    // short L1-only lease (see NICK_404_TTL_SEC), not the durable one the parse-sourced NICKED
    // below gets. NICKED is the only non-ok state this branch can produce.
    if (!scraped.retry429 && !trippedBreaker) {
      writeCached(player, scraped.body, env, ctx,
        scraped.body.state === "NICKED" ? NICK_404_TTL_SEC : NEG_TTL_SEC);
    }
    return { body: scraped.body, retry429: scraped.retry429 === true };
  }

  const parsed = parseBedwarsFromHtml(scraped.html, player);
  if (parsed.success === true) {
    const profile = parseProfile(scraped.html);
    if (profile.displayName) parsed.displayName = profile.displayName;
    parsed.rank = profile.rank;
    writeCached(player, parsed, env, ctx);
  } else {
    // NICKED is a stable page state (the profile page just has no Bedwars section) so it
    // caches like a success; other parse failures are transient and cache briefly.
    writeCached(player, parsed, env, ctx, parsed.state === "NICKED" ? NICKED_TTL_SEC : NEG_TTL_SEC);
  }
  return { body: parsed };
}

/**
 * Per-isolate per-player single-flight around the origin claim + scrape + retry loop.
 *
 * The entry is registered BEFORE the origin-slot wait, so a client fallback single that races a
 * still-running batch fetch of the same player joins that fetch instead of missing it while the
 * batch worker is still queued at the gate - one upstream call, one source, regardless of which
 * source the call chose. The entry clears only AFTER the result's cache writes commit, so a
 * lookup landing in the gap finds either the in-flight entry or the cache, never neither; the
 * cleanup chain rides ctx.waitUntil so a joiner is never orphaned by the initiating request
 * ending first. Joiners share the initiator's terminal result (retries included); 429 retries
 * re-claim at the initiator's lane exactly as each caller's own loop did before.
 *
 * The map is keyed per player, NOT per source: a joiner shares the in-flight result even when
 * its own request pinned the other source. Each individual upstream fetch still uses exactly
 * one source - the invariant - and the joiner merely reuses a result that already exists
 * instead of issuing a second fetch.
 */
const IN_FLIGHT = new Map(); // playerLower -> promise of { body, retry429? } (terminal, post-retries)

function scrapeShared(player, env, ctx, lane, maxAttempts, source) {
  const key = player.toLowerCase();
  const existing = IN_FLIGHT.get(key);
  if (existing) return existing;
  const writes = [];
  const trackedCtx = {
    waitUntil(p) {
      writes.push(Promise.resolve(p).catch(() => {}));
      ctx.waitUntil(p);
    },
  };
  const promise = (async () => {
    let r;
    for (let attempt = 1; attempt <= maxAttempts; attempt++) {
      await claimOrigin(lane); // a retry re-claims at the SAME lane
      r = await scrapeAndCache(player, env, trackedCtx, source);
      if (!r.retry429) break;
      noteOrigin429(attempt); // throttles every origin path, not just this caller
    }
    return r;
  })();
  IN_FLIGHT.set(key, promise);
  ctx.waitUntil(
    promise
      .catch(() => {})
      .then(() => Promise.all(writes))
      .finally(() => { if (IN_FLIGHT.get(key) === promise) IN_FLIGHT.delete(key); })
  );
  return promise;
}

/** Single-player resolution: cache (unless fresh) then scrape. */
export async function getBedwars(player, env, ctx, fresh, lane = LANE_LOW) {
  let body;
  if (!fresh) {
    const cached = await readCached(player, env, ctx);
    if (cached) body = { ...cached, cached: true };
  }
  if (!body) {
    // Source pinned ONCE per HTTP request: while the breaker is set (hypixel serving challenge
    // pages) the whole lookup goes to shmeado; otherwise it is hypixel for the request's full
    // retry loop - the breaker is never re-read mid-request. The single route claims origin
    // slots from the same gate as the batch pool, so it never increases throughput - the lane
    // only decides who is served first. 429s retry (they are never negatively cached); the
    // terminal body is whatever the last attempt produced, exactly as before.
    const source = (await readBlocked(env)) ? "shmeado" : "hypixel";
    const r = await scrapeShared(player, env, ctx, lane, SINGLE_MAX_ATTEMPTS, source);
    body = r.body;
  }
  return body;
}

/**
 * Run items through fn with bounded concurrency. fn returns a TERMINAL result - origin pacing,
 * 429 retries and single-flight all live in scrapeShared. Calls onResult(item, terminalResult)
 * exactly once per item.
 */
async function scrapePool(items, fn, onResult) {
  let idx = 0;

  async function runOne(name) {
    const r = await fn(name);
    if (onResult) await onResult(name, r);
    return r;
  }

  async function worker() {
    while (idx < items.length) await runOne(items[idx++]);
  }

  const n = Math.min(BATCH_CFG.concurrency, items.length);
  await Promise.all(Array.from({ length: Math.max(0, n) }, worker));
}

function dedupeValidate(names) {
  const valid = [];
  const seen = new Set();
  for (const raw of names) {
    const name = (raw || "").trim();
    const key = name.toLowerCase();
    if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || seen.has(key)) continue;
    seen.add(key);
    valid.push(name);
    if (valid.length >= MAX_BATCH) break;
  }
  return valid;
}

/**
 * Streaming batch: returns a Response that emits one NDJSON line per player AS it resolves — cache
 * hits first (instant), then cold scrapes paced at ~1.6/s. The client reads line-by-line and renders
 * each player the moment its line arrives, so a partly-warm lobby shows most stats in well under a
 * second and an all-cold lobby fills in progressively instead of blocking on the slowest player.
 * Each line carries a "name" field (the requested name) so the client can map it back.
 */
export function streamBedwarsBatch(names, env, ctx, urchinCtx, seraphCtx, lane = LANE_LOW) {
  const valid = dedupeValidate(names);
  const { readable, writable } = new TransformStream();
  const writer = writable.getWriter();
  const enc = new TextEncoder();
  const writeLine = (name, body) =>
    writer.write(enc.encode(JSON.stringify({ name, ...body }) + "\n"));

  // Urchin tags resolve in parallel with the counter stream (one Coral batch for the
  // eligible uuids); results attach inline when already settled, otherwise as
  // "urchinUpdate" follow-up lines AFTER the counter pass so a follow-up can never
  // precede its base line. Errors -> no lines (silent degradation).
  // The requester's auth identity rides in each provider ctx and is threaded into every
  // lookup: it selects the key, receives failure attribution, and drives the per-requester
  // settlement when this request piggybacks another identity's in-flight upstream call.
  const urchinPromise =
    urchinCtx && urchinCtx.uuidByName && urchinCtx.uuidByName.size > 0 && !urchinCtx.unavailableOnly
      ? tagsForUuids([...urchinCtx.uuidByName.values()], env, ctx, urchinCtx.auth).catch(() => new Map())
      : null;
  let urchinResults = null;
  if (urchinPromise) {
    urchinPromise.then((m) => { urchinResults = m; });
    // Guarantee the lookup (and its cache writes) completes even if the stream finishes
    // first - the 3 s race below may abandon it, but the Worker lifecycle must not.
    ctx.waitUntil(urchinPromise.then(() => {}));
  }
  const urchinFieldsFor = (name) => {
    if (!urchinResults || !urchinCtx) return null;
    const uuid = urchinCtx.uuidByName.get(name.toLowerCase());
    if (!uuid) return null;
    const f = resultFields(urchinResults.get(uuid), uuid);
    // urchinUuid alone (no metadata) is meaningless - require actual resolution fields.
    return Object.keys(f).length > 1 || (Object.keys(f).length === 1 && !f.urchinUuid) ? f : null;
  };

  // Seraph tags resolve in parallel with the same shape (per-UUID fan-out; §7d no cache), attaching
  // inline when settled or as "seraphUpdate" follow-up lines after the counter pass.
  const seraphPromise =
    seraphCtx && seraphCtx.uuidByName && seraphCtx.uuidByName.size > 0 && !seraphCtx.unavailableOnly
      ? seraphTagsForUuids([...seraphCtx.uuidByName.values()], env, ctx, seraphCtx.auth).catch(() => new Map())
      : null;
  let seraphResults = null;
  if (seraphPromise) {
    seraphPromise.then((m) => { seraphResults = m; });
    ctx.waitUntil(seraphPromise.then(() => {}));
  }
  const seraphFieldsFor = (name) => {
    if (!seraphResults || !seraphCtx) return null;
    const uuid = seraphCtx.uuidByName.get(name.toLowerCase());
    if (!uuid) return null;
    const f = seraphResultFields(seraphResults.get(uuid), uuid);
    // seraphUuid alone (no metadata) is meaningless - require actual resolution fields.
    return Object.keys(f).length > 1 || (Object.keys(f).length === 1 && !f.seraphUuid) ? f : null;
  };

  ctx.waitUntil(
    (async () => {
      try {
        // Names whose urchin/seraph result was NOT ready at base-line time (follow-up pass).
        const needUrchin = [];
        const needSeraph = [];
        const emitLine = async (name, body) => {
          if (urchinCtx && urchinCtx.unavailableOnly && urchinCtx.uuidByName.has(name.toLowerCase())) {
            body.urchinUnavailable = true; // no KV -> zero Coral calls, resolved unavailable
          } else if (urchinPromise && urchinCtx.uuidByName.has(name.toLowerCase())) {
            const f = urchinFieldsFor(name);
            if (f) Object.assign(body, f);
            else needUrchin.push(name);
          }
          if (seraphCtx && seraphCtx.unavailableOnly && seraphCtx.uuidByName.has(name.toLowerCase())) {
            body.seraphUnavailable = true; // no key source -> zero Seraph calls, resolved unavailable
          } else if (seraphPromise && seraphCtx.uuidByName.has(name.toLowerCase())) {
            const f = seraphFieldsFor(name);
            if (f) Object.assign(body, f);
            else needSeraph.push(name);
          }
          await writeLine(name, body);
        };

        // 1) Emit cache hits immediately.
        const misses = [];
        for (const name of valid) {
          const cached = await readCached(name, env, ctx);
          if (cached) await emitLine(name, { ...cached, cached: true });
          else misses.push(name);
        }
        // 2) Scrape counter misses politely, emitting each as it terminally resolves. The
        //    source is pinned ONCE for the whole request: a breaker transition mid-batch never
        //    flips the remaining misses to the other source.
        if (misses.length > 0) {
          const source = (await readBlocked(env)) ? "shmeado" : "hypixel";
          await scrapePool(
            misses,
            (name) => scrapeShared(name, env, ctx, lane, BATCH_CFG.maxAttempts, source),
            async (name, r) =>
              emitLine(name, r && r.body ? r.body : { success: false, state: "ERROR", displayName: name })
          );
        }
        // 3) Urchin follow-ups: every base line has been emitted, so an urchinUpdate can
        //    never precede its base. Wait up to 3 s for the Coral batch; a timed-out
        //    lookup still caches (its promise was launched under ctx.waitUntil-covered
        //    work above), just without follow-up lines this request.
        if (urchinPromise && needUrchin.length > 0) {
          await Promise.race([urchinPromise, sleep(3000)]);
          for (const name of needUrchin) {
            const f = urchinFieldsFor(name);
            if (f) await writeLine(name, { success: true, urchinUpdate: true, ...f });
          }
        }
        // 4) Seraph follow-ups, same invariant: emitted only after every base line.
        if (seraphPromise && needSeraph.length > 0) {
          await Promise.race([seraphPromise, sleep(3000)]);
          for (const name of needSeraph) {
            const f = seraphFieldsFor(name);
            if (f) await writeLine(name, { success: true, seraphUpdate: true, ...f });
          }
        }
      } catch (e) {
        try { await writeLine("", { success: false, state: "ERROR", error: String(e && e.message ? e.message : e) }); } catch (_) {}
      } finally {
        try { await writer.close(); } catch (_) {}
      }
    })()
  );

  return new Response(readable, {
    headers: {
      "content-type": "application/x-ndjson; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}
