/**
 * Cache for parsed Bedwars stats: L1 only.
 *
 *   L1 = caches.default  (per-colo, ephemeral, ~ms, may evict early)
 *
 * Player stats are never written to KV. The STATS_KV binding is retained only for the
 * Urchin/Seraph API key + backoff state and the "origin blocked" circuit breaker below,
 * and it stays optional: a deployment without it just loses those.
 */

export const CACHE_TTL_SEC = 900; // 15m, matches the mod's disk cache
// Negative caching: failures are cached briefly so a broken/nicked player can't trigger a
// re-scrape on every lookup, but short enough that transient upstream trouble self-heals.
export const NEG_TTL_SEC = 90; // transient failures (fetch error, blocked, parse_failed, other http_*)
export const NICKED_TTL_SEC = 900; // NICKED is a stable page state; match the counter TTL
const BLOCKED_TTL_SEC = 120; // global "origin blocked" circuit breaker

function l1Key(player) {
  return new Request(`https://bedwarsqol.internal/cache/bedwars/v2/${player.toLowerCase()}`);
}

function cacheableResponse(parsed, ttlSec = CACHE_TTL_SEC) {
  return new Response(JSON.stringify(parsed), {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${ttlSec}`,
    },
  });
}

/** Returns the cached body on hit, or null. */
export async function readCached(player, env) {
  const l1 = await caches.default.match(l1Key(player));
  return l1 ? await l1.json() : null;
}

/** Writes a parse (positive or negative) to L1 (fire-and-forget via ctx.waitUntil). */
export function writeCached(player, parsed, env, ctx, ttlSec = CACHE_TTL_SEC) {
  ctx.waitUntil(caches.default.put(l1Key(player), cacheableResponse(parsed, ttlSec).clone()));
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
