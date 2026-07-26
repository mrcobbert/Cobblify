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
  readCached, writeCached, readStar, readBlocked, writeBlocked, NEG_TTL_SEC, NICKED_TTL_SEC,
} from "./cache.js";
import { scrapeStarForPool } from "./star.js";
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
  minSpacingMs: 620, // ~1.6/s — under the ~2/s 429 threshold measured on this IP
  maxAttempts: 4,
  backoffSpacingMs: 1300, // collapse to ~today's proven-safe rate on a 429
  backoffBaseMs: 1200,
};
const MAX_BATCH = 24;

// Star scrapes run AFTER the counter pass (sequentially, in the same batch) so they never overlap it
// and stack origin load. Gentler than the counter pace since they're never on the critical path.
const STAR_CFG = {
  concurrency: 1,
  minSpacingMs: 720,
  maxAttempts: 3,
  backoffSpacingMs: 1300,
  backoffBaseMs: 1200,
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
    parsed.networkLevel = profile.networkLevel ?? 0;
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
 * Overlay the Bedwars star onto a resolved body. If the star is cached (the common case once any user
 * has warmed it), attach it inline at zero extra latency. If not, kick off a background scrape so the
 * NEXT lookup has it — never blocking this response on the second page. The client falls back to the
 * network level until the real star lands.
 */
async function overlayStar(player, body, env, ctx) {
  if (!body || body.success !== true) return body;
  const cached = await readStar(player, env, ctx);
  if (cached != null) {
    body.bedwarsLevel = cached;
  } else {
    ctx.waitUntil(scrapeStarForPool(player, env, ctx).catch(() => {}));
  }
  return body;
}

/** Single-player resolution: two-tier cache (unless fresh) then scrape, with star overlaid. */
export async function getBedwars(player, env, ctx, fresh) {
  let body;
  if (!fresh) {
    const cached = await readCached(player, env, ctx);
    if (cached) body = { ...cached.body, cached: true };
  }
  if (!body) body = (await scrapeAndCache(player, env, ctx)).body;
  return await overlayStar(player, body, env, ctx);
}

/**
 * Run items through fn with bounded concurrency, min spacing between starts, and shared 429 backoff.
 * Calls onResult(item, terminalResult) exactly once per item (after retries settle).
 */
async function scrapePool(items, fn, cfg, onResult) {
  const state = { nextStart: 0, spacing: cfg.minSpacingMs, backoffUntil: 0 };
  let idx = 0;

  async function gate() {
    const now = Date.now();
    const start = Math.max(now, state.nextStart, state.backoffUntil); // synchronous claim -> serialized
    state.nextStart = start + state.spacing;
    const wait = start - now;
    if (wait > 0) await sleep(wait);
  }

  async function runOne(name) {
    let r;
    for (let attempt = 1; attempt <= cfg.maxAttempts; attempt++) {
      await gate();
      r = await fn(name);
      if (!r || !r.retry429) break;
      state.spacing = Math.max(state.spacing, cfg.backoffSpacingMs); // throttle everyone
      state.backoffUntil = Date.now() + cfg.backoffBaseMs * attempt;
    }
    if (onResult) await onResult(name, r);
    return r;
  }

  async function worker() {
    while (idx < items.length) await runOne(items[idx++]);
  }

  const n = Math.min(cfg.concurrency, items.length);
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
export function streamBedwarsBatch(names, env, ctx, urchinCtx, seraphCtx) {
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
        // Players still missing a star after the counter pass — scraped (or background-warmed) below.
        const needStar = [];
        // Emit one counter line, overlaying the star if it's already cached (warm = instant). A cold
        // star is recorded for the star pass instead of blocking the counter line.
        // Names whose urchin/seraph result was NOT ready at base-line time (follow-up pass).
        const needUrchin = [];
        const needSeraph = [];
        const emitWithStar = async (name, body) => {
          if (body && body.success === true) {
            const s = await readStar(name, env, ctx);
            if (s != null) body.bedwarsLevel = s;
            else needStar.push(name);
          }
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
          if (cached) await emitWithStar(name, { ...cached.body, cached: true });
          else misses.push(name);
        }
        // 2) Scrape counter misses politely, emitting each as it terminally resolves.
        await scrapePool(
          misses,
          (name) => scrapeAndCache(name, env, ctx),
          BATCH_CFG,
          async (name, r) =>
            emitWithStar(name, r && r.body ? r.body : { success: false, state: "ERROR", displayName: name })
        );
        // 3) Star pass (after counters, so it can't stack origin load): scrape each uncached star and
        //    stream a lightweight "starUpdate" line so the client upgrades the number in the same game.
        await scrapePool(
          needStar,
          (name) => scrapeStarForPool(name, env, ctx),
          STAR_CFG,
          async (name, r) => {
            if (r && r.level != null) {
              await writeLine(name, { success: true, starUpdate: true, bedwarsLevel: r.level });
            }
          }
        );
        // 4) Urchin follow-ups: every base line has been emitted, so an urchinUpdate can
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
        // 5) Seraph follow-ups, same invariant: emitted only after every base line.
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
