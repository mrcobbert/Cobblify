/**
 * Two-tier cache for parsed Bedwars stats.
 *
 *   L1 = caches.default  (per-colo, ephemeral, ~ms, may evict early)
 *   L2 = Workers KV      (global, eventually-consistent ≤60s, survives colo eviction)
 *
 * KV is optional: if the STATS_KV binding is absent (a friend's no-KV deployment) we degrade
 * cleanly to L1-only. On an L2 hit we re-populate L1 so subsequent same-colo reads are edge-fast.
 */

export const CACHE_TTL_SEC = 900; // 15m, matches the mod's disk cache
// Negative caching: failures are cached briefly so a broken/nicked player can't trigger a
// re-scrape on every lookup, but short enough that transient upstream trouble self-heals.
export const NEG_TTL_SEC = 90; // transient failures (fetch error, blocked, parse_failed, other http_*)
export const NICKED_TTL_SEC = 900; // NICKED is a stable page state; match the counter TTL
const BLOCKED_TTL_SEC = 120; // global "origin blocked" circuit breaker
// The Bedwars star (level) moves ~1 per several games, so it's safe to cache far longer than the
// counters. A long TTL is what keeps the extra achievements-page fetch rare: once any user warms a
// player's star into KV, everyone serves it for hours without re-scraping. A few-star lag on an
// active grinder is invisible for a "next to the name" number.
export const STAR_TTL_SEC = 21600; // 6h

function l1Key(player) {
  return new Request(`https://bedwarsqol.internal/cache/bedwars/v2/${player.toLowerCase()}`);
}
function l2Key(player) {
  return `bw:v2:${player.toLowerCase()}`;
}
function starL1Key(player) {
  return new Request(`https://bedwarsqol.internal/cache/star/v1/${player.toLowerCase()}`);
}
function starL2Key(player) {
  return `bw:star:v1:${player.toLowerCase()}`;
}

function cacheableResponse(parsed, ttlSec = CACHE_TTL_SEC) {
  return new Response(JSON.stringify(parsed), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${ttlSec}`,
    },
  });
}

/** Returns { body, tier:"edge"|"kv" } on hit, or null. Re-warms L1 from an L2 hit. */
export async function readCached(player, env, ctx) {
  const cache = caches.default;
  const l1 = await cache.match(l1Key(player));
  if (l1) return { body: await l1.json(), tier: "edge" };

  if (env && env.STATS_KV) {
    try {
      const kv = await env.STATS_KV.get(l2Key(player), "json");
      if (kv) {
        // Re-warm L1 (fire-and-forget) so the next read in this colo skips KV.
        if (ctx) ctx.waitUntil(cache.put(l1Key(player), cacheableResponse(kv).clone()));
        return { body: kv, tier: "kv" };
      }
    } catch (_) { /* KV hiccup -> treat as miss */ }
  }
  return null;
}

/** Writes a parse (positive or negative) to both tiers (fire-and-forget via ctx.waitUntil). */
export function writeCached(player, parsed, env, ctx, ttlSec = CACHE_TTL_SEC) {
  const cache = caches.default;
  ctx.waitUntil(cache.put(l1Key(player), cacheableResponse(parsed, ttlSec).clone()));
  if (env && env.STATS_KV) {
    // KV allows ≤1 write/sec/key; a stray 429 here is harmless (L1 still serves).
    ctx.waitUntil(
      env.STATS_KV.put(l2Key(player), JSON.stringify(parsed), { expirationTtl: ttlSec })
        .catch(() => {})
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

// ---- Bedwars star (level) — separate, long-lived cache --------------------

function starResponse(level) {
  return new Response(JSON.stringify({ level }), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${STAR_TTL_SEC}`,
    },
  });
}

/** Returns the cached Bedwars star (int) or null. Re-warms L1 from an L2 hit. */
export async function readStar(player, env, ctx) {
  const cache = caches.default;
  const l1 = await cache.match(starL1Key(player));
  if (l1) {
    const v = await l1.json();
    if (v && Number.isFinite(v.level)) return v.level;
  }
  if (env && env.STATS_KV) {
    try {
      const kv = await env.STATS_KV.get(starL2Key(player), "json");
      if (kv && Number.isFinite(kv.level)) {
        if (ctx) ctx.waitUntil(cache.put(starL1Key(player), starResponse(kv.level).clone()));
        return kv.level;
      }
    } catch (_) { /* KV hiccup -> treat as miss */ }
  }
  return null;
}

/** Writes a resolved star to both tiers (fire-and-forget via ctx.waitUntil). */
export function writeStar(player, level, env, ctx) {
  if (!Number.isFinite(level) || level < 0) return;
  const cache = caches.default;
  ctx.waitUntil(cache.put(starL1Key(player), starResponse(level).clone()));
  if (env && env.STATS_KV) {
    ctx.waitUntil(
      env.STATS_KV.put(starL2Key(player), JSON.stringify({ level }), { expirationTtl: STAR_TTL_SEC })
        .catch(() => {})
    );
  }
}
