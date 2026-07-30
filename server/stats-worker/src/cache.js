/**
 * Cache for parsed Bedwars stats: two tiers.
 *
 *   L1 = caches.default  (per-colo, ephemeral, ~ms, may evict early)
 *   L2 = Workers KV      (global, eventually consistent <= ~60 s, optional STATS_KV binding)
 *
 * Only POSITIVE parses and NICKED (a stable page state) are written to KV, so a shared
 * deployment reuses scrapes across users and colos. Transient negatives (fetch error,
 * blocked, parse_failed, other http_*) stay L1-only: one colo's momentary failure must
 * never poison other colos. KV values embed their write timestamp because KV expiration is
 * not exact - a read past the TTL is discarded, and an L1 re-warm from a KV hit uses only
 * the REMAINING ttl (a near-expiry record must not buy itself a fresh 15 minutes in a new
 * colo). Without the STATS_KV binding this degrades to exactly the L1-only behavior.
 */

export const CACHE_TTL_SEC = 900; // 15m, matches the mod's disk cache
// Negative caching: failures are cached briefly so a broken/nicked player can't trigger a
// re-scrape on every lookup, but short enough that transient upstream trouble self-heals.
export const NEG_TTL_SEC = 90; // transient failures (fetch error, blocked, parse_failed, other http_*)
export const NICKED_TTL_SEC = 900; // NICKED is a stable page state; match the counter TTL
const BLOCKED_TTL_SEC = 120; // global "origin blocked" circuit breaker
const KV_MIN_REMAINING_SEC = 30; // a KV record this close to expiry is not worth re-warming

function l1Key(player) {
  return new Request(`https://bedwarsqol.internal/cache/bedwars/v2/${player.toLowerCase()}`);
}

function l2Key(player) {
  return `bw:v2:${player.toLowerCase()}`;
}

function cacheableResponse(parsed, ttlSec = CACHE_TTL_SEC) {
  return new Response(JSON.stringify(parsed), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${ttlSec}`,
    },
  });
}

/** Returns the cached body on hit (either tier), or null. A KV hit re-warms L1 with the
 *  remaining ttl (fire-and-forget via ctx.waitUntil). */
export async function readCached(player, env, ctx) {
  const l1 = await caches.default.match(l1Key(player));
  if (l1) return await l1.json();
  if (!(env && env.STATS_KV)) return null;
  let record;
  try {
    record = await env.STATS_KV.get(l2Key(player), "json");
  } catch (_) {
    return null; // KV hiccup -> miss
  }
  if (!record || !Number.isFinite(record.writtenAt) || !Number.isFinite(record.ttlSec)) return null;
  const remainingSec = record.ttlSec - (Date.now() - record.writtenAt) / 1000;
  if (remainingSec < KV_MIN_REMAINING_SEC) return null;
  if (ctx) {
    ctx.waitUntil(
      caches.default
        .put(l1Key(player), cacheableResponse(record.body, Math.floor(remainingSec)))
        .catch(() => {})
    );
  }
  return record.body;
}

/** Writes a parse (positive or negative) to L1, and durable outcomes (positive parses and
 *  NICKED - never transient negatives) to KV when bound (fire-and-forget via ctx.waitUntil). */
export function writeCached(player, parsed, env, ctx, ttlSec = CACHE_TTL_SEC) {
  ctx.waitUntil(caches.default.put(l1Key(player), cacheableResponse(parsed, ttlSec).clone()));
  const durable = ttlSec === CACHE_TTL_SEC || ttlSec === NICKED_TTL_SEC;
  if (durable && env && env.STATS_KV) {
    ctx.waitUntil(
      env.STATS_KV.put(
        l2Key(player),
        JSON.stringify({ writtenAt: Date.now(), ttlSec, body: parsed }),
        { expirationTtl: ttlSec }
      ).catch(() => {})
    );
  }
}

// ---- Global "origin blocked" circuit breaker ------------------------------
// When hypixel's edge serves us a challenge page, EVERY scrape is doomed until the block
// lifts, so a short global flag stops the worker from hammering the origin (which would
// only prolong the block) and short-circuits lookups to an instant error instead.

function blockedL1Key() {
  return new Request("https://bedwarsqol.internal/cache/blocked/v1");
}
const BLOCKED_L2 = "bw:blocked:v1";

/** True while the "origin blocked" flag is set (either tier). */
export async function readBlocked(env) {
  if (await caches.default.match(blockedL1Key())) return true;
  if (env && env.STATS_KV) {
    try { if (await env.STATS_KV.get(BLOCKED_L2)) return true; } catch (_) { /* KV hiccup -> unset */ }
  }
  return false;
}

/** Sets the "origin blocked" flag in both tiers (fire-and-forget via ctx.waitUntil). */
export function writeBlocked(env, ctx) {
  ctx.waitUntil(caches.default.put(
    blockedL1Key(),
    new Response("1", { headers: { "cache-control": `public, max-age=${BLOCKED_TTL_SEC}` } })
  ));
  if (env && env.STATS_KV) {
    ctx.waitUntil(
      env.STATS_KV.put(BLOCKED_L2, "1", { expirationTtl: BLOCKED_TTL_SEC }).catch(() => {})
    );
  }
}
