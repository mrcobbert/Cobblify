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
  readCached, writeCached, readBlocked, writeBlocked, NEG_TTL_SEC, NICKED_TTL_SEC,
} from "./cache.js";
import { tagsForUuids, resultFields } from "./urchin.js";
import { tagsForUuids as seraphTagsForUuids, resultFields as seraphResultFields } from "./seraph.js";

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|challenge-platform|attention required/i;

// Header + full Bedwars block live in the first ~21% (~55 KB decompressed) of the ~258 KB page.
const NEEDLE = "stats-content-bedwars";
const BEDWARS_CUSHION = 22_000; // bytes to keep reading past the needle to capture the whole block
const MAX_PREFIX_BYTES = 90_000; // hard stop; also fully contains any <50 KB challenge page

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

/** Fetch + stream-read a player page. Returns { ok, html } or { ok:false, body, retry429? }. */
export async function scrapePlayerHtml(player, env) {
  const base = (env && env.HYPIXEL_BASE) || "https://hypixel.net";
  const target = `${base}/player/${encodeURIComponent(player)}`;
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
    });
  } catch (e) {
    return { ok: false, body: errBody(player, String(e && e.message ? e.message : e)) };
  }

  // Rate-limited by hypixel: signal the pool to back off and retry.
  if (response.status === 429) {
    try { await response.body?.cancel(); } catch (_) {}
    return { ok: false, retry429: true, body: errBody(player, "http_429", 429) };
  }

  let html;
  try { html = await readHtmlPrefix(response); }
  catch (e) { return { ok: false, body: errBody(player, "stream_" + String(e && e.message ? e.message : e)) }; }

  if (response.status === 403 || (html.length < 50_000 && CHALLENGE_RE.test(html))) {
    return { ok: false, body: errBody(player, "blocked_by_cloudflare", response.status) };
  }
  if (response.status < 200 || response.status >= 300) {
    return { ok: false, body: errBody(player, "http_" + response.status, response.status) };
  }
  return { ok: true, html };
}

/** Scrape one player, parse, merge profile header, and cache the outcome. Returns { body, retry429? }. */
async function scrapeAndCache(player, env, ctx) {
  // Circuit breaker: while the origin is serving challenge pages every scrape is doomed, so
  // fail instantly without touching hypixel. The short-circuit body is NOT cached (the flag
  // already throttles; caching it would outlive the flag's own TTL per player).
  if (await readBlocked(env)) {
    return { body: errBody(player, "blocked_by_cloudflare") };
  }
  const scraped = await scrapePlayerHtml(player, env);
  if (!scraped.ok) {
    if (scraped.body && scraped.body.error === "blocked_by_cloudflare") writeBlocked(env, ctx);
    // 429s are NOT negatively cached: the pool retries them, and a cached terminal 429
    // would poison the very lookups the backoff is about to make succeed.
    if (!scraped.retry429) writeCached(player, scraped.body, env, ctx, NEG_TTL_SEC);
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

/** Single-player resolution: cache (unless fresh) then scrape. */
export async function getBedwars(player, env, ctx, fresh, lane = LANE_LOW) {
  let body;
  if (!fresh) {
    const cached = await readCached(player, env);
    if (cached) body = { ...cached, cached: true };
  }
  if (!body) {
    // The single route claims origin slots from the same gate as the batch pool, so it never
    // increases throughput - the lane only decides who is served first. 429s retry (they are never
    // negatively cached); the terminal body is whatever the last attempt produced, exactly as before.
    for (let attempt = 1; attempt <= SINGLE_MAX_ATTEMPTS; attempt++) {
      await claimOrigin(lane);
      const r = await scrapeAndCache(player, env, ctx);
      body = r.body;
      if (!r.retry429) break;
      noteOrigin429(attempt);
    }
  }
  return body;
}

/**
 * Run items through fn with bounded concurrency, pacing every start through the shared origin gate
 * in `lane` (so the pool never races the single route), and shared 429 backoff.
 * Calls onResult(item, terminalResult) exactly once per item (after retries settle).
 */
async function scrapePool(items, fn, onResult, lane) {
  let idx = 0;

  async function runOne(name) {
    let r;
    for (let attempt = 1; attempt <= BATCH_CFG.maxAttempts; attempt++) {
      await claimOrigin(lane); // a retry re-claims at the SAME lane
      r = await fn(name);
      if (!r || !r.retry429) break;
      noteOrigin429(attempt); // throttles every origin path, not just this pool
    }
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
  const urchinPromise =
    urchinCtx && urchinCtx.uuidByName && urchinCtx.uuidByName.size > 0 && !urchinCtx.unavailableOnly
      ? tagsForUuids([...urchinCtx.uuidByName.values()], env, ctx).catch(() => new Map())
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
      ? seraphTagsForUuids([...seraphCtx.uuidByName.values()], env, ctx).catch(() => new Map())
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
          const cached = await readCached(name, env);
          if (cached) await emitLine(name, { ...cached, cached: true });
          else misses.push(name);
        }
        // 2) Scrape counter misses politely, emitting each as it terminally resolves.
        await scrapePool(
          misses,
          (name) => scrapeAndCache(name, env, ctx),
          async (name, r) =>
            emitLine(name, r && r.body ? r.body : { success: false, state: "ERROR", displayName: name }),
          lane
        );
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
