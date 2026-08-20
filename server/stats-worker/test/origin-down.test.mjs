/**
 * The OTHER way hypixel stops answering: its origin fails behind a healthy Cloudflare edge.
 *
 * Regression origin (2026-08-19): hypixel.net served 522 (origin connect timeout, ~20 s to
 * arrive) and 523 (origin unreachable) for every name not already warm in Cloudflare's edge
 * cache. Measured live from two vantage points, so this was hypixel-side, not this worker:
 * `/player/spelting` and `/player/yoonie` -> 522 after 19.7 s, `/player/gamerboy81` -> 523,
 * while `/player/Technoblade` (edge-cached) still returned 200. shmeado served all three of
 * the failing names correctly at the same moment.
 *
 * The fallback never engaged. Two independent reasons, both fixed here:
 *
 *   1. Only `blocked_by_cloudflare` (a challenge page or a 403 - the EDGE refusing this worker)
 *      called writeBlocked(). A 520-527 fell through to the generic `http_<code>` branch, so the
 *      breaker stayed unset, so the source stayed pinned to hypixel and shmeado was never asked.
 *
 *   2. Even once tripped, the failure had been written to the 90 s negative cache - and
 *      readCached runs BEFORE the source is pinned. The cached hypixel error would have been
 *      served in preference to the fallback for most of the breaker's own 120 s life.
 *
 * So the rule is now: a failure that trips the breaker is never negatively cached. That applies
 * to `blocked_by_cloudflare` too, which had reason 2 all along - it was just masked, because a
 * challenge page trips the breaker for every OTHER player at the same time and only the player
 * who hit the block was shadowed by their own cached error.
 *
 * Stub style follows shmeado-fallback.test.mjs (mocked clock, stubbed fetch/caches/KV, no
 * sockets). Node's test runner gives each file its own process, so module-level state (the
 * origin gate, IN_FLIGHT) is shared only within this file; nextClock() steps each test's
 * mocked clock 10 minutes past anything a previous test could have claimed.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { scrapePlayerHtml, getBedwars } from "../src/scrape.js";
import { readBlocked } from "../src/cache.js";

const BEEPOR_HTML = readFileSync(
  fileURLToPath(new URL("./fixtures/shmeado-ok-beepor.html", import.meta.url)), "utf8");

let clockBase = 2_400_000_000_000;
const nextClock = () => (clockBase += 10 * 60 * 1000);

/** Cloudflare's origin-error interstitial: short, and carrying none of the challenge wording. */
const ORIGIN_ERROR_PAGE =
  "<html><head><title>522: Connection timed out</title></head>" +
  "<body><h1>Error 522</h1><h2>Connection timed out</h2></body></html>";

// ---- stubs (same shapes as shmeado-fallback.test.mjs) -----------------------

function stubCaches() {
  const store = new Map();
  globalThis.caches = {
    default: {
      async match(req) {
        const r = store.get(req.url);
        return r ? r.clone() : undefined;
      },
      async put(req, res) { store.set(req.url, res); },
    },
  };
  return store;
}

function stubKv() {
  const store = new Map();
  const puts = [];
  return {
    async get(key, type) {
      const v = store.get(key);
      if (v == null) return null;
      return type === "json" ? JSON.parse(v) : v;
    },
    async put(key, value) { puts.push(key); store.set(key, String(value)); },
    puts,
  };
}

function routeFetch(routes) {
  const hits = [];
  globalThis.fetch = async (url, init) => {
    const u = String(url);
    hits.push(u);
    for (const [pattern, reply] of routes) {
      if (u.includes(pattern)) return reply(u, init);
    }
    throw new Error("unrouted fetch " + u);
  };
  const count = (host) => hits.filter((u) => u.startsWith(host)).length;
  return {
    hypixel: () => count("https://hypixel.net/"),
    shmeado: () => count("https://shmeado.club/"),
  };
}

function makeCtx() {
  const pending = [];
  return {
    ctx: { waitUntil(p) { pending.push(Promise.resolve(p).catch(() => {})); } },
    async settle() {
      while (pending.length) await Promise.all(pending.splice(0));
    },
  };
}

async function runMocked(t, work, maxMs = 120_000, stepMs = 100) {
  let done = false;
  const p = work().then(
    (v) => { done = true; return v; },
    (e) => { done = true; throw e; }
  );
  p.catch(() => {});
  for (let elapsed = 0; elapsed < maxMs && !done; elapsed += stepMs) {
    await new Promise((r) => setImmediate(r));
    if (done) break;
    t.mock.timers.tick(stepMs);
  }
  await new Promise((r) => setImmediate(r));
  return p;
}

// ---- classification --------------------------------------------------------

test("every Cloudflare origin-error status is originDown, keeping its own http_<code>", async () => {
  for (const status of [520, 521, 522, 523, 524, 525, 526, 527]) {
    globalThis.fetch = async () => new Response(ORIGIN_ERROR_PAGE, { status });
    const r = await scrapePlayerHtml("SomeGuy", {});
    assert.equal(r.ok, false, `${status} must not be ok`);
    assert.equal(r.originDown, true, `${status} must be originDown`);
    assert.equal(r.body.error, "http_" + status);
    assert.equal(r.body.httpStatus, status);
    // Who is failing stays honest: hypixel's origin, not the edge blocking us.
    assert.notEqual(r.body.error, "blocked_by_cloudflare");
  }
});

test("the origin-error range is exact: 519 and 528 stay plain http_<code>", async () => {
  for (const status of [519, 528]) {
    globalThis.fetch = async () => new Response(ORIGIN_ERROR_PAGE, { status });
    const r = await scrapePlayerHtml("SomeGuy", {});
    assert.equal(r.ok, false);
    assert.equal(r.originDown, undefined, `${status} must not trip the breaker`);
    assert.equal(r.body.error, "http_" + status);
  }
});

test("a 500 is not an origin-error and never becomes originDown", async () => {
  globalThis.fetch = async () => new Response("<html><body>oops</body></html>", { status: 500 });
  const r = await scrapePlayerHtml("SomeGuy", {});
  assert.equal(r.originDown, undefined);
  assert.equal(r.body.error, "http_500");
});

test("a fetch that outlives the bound is aborted and classified originDown", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  // Models a real signal-aware fetch against a hung origin: it settles only on abort. Without
  // the bound this is the ~20 s hang that held an origin-gate slot per lookup; with a bound
  // that fires BEFORE Cloudflare's own 522, the abort has to carry the verdict instead.
  globalThis.fetch = async (_url, init) =>
    new Promise((_resolve, reject) => {
      init.signal.addEventListener("abort", () => reject(new Error("The operation was aborted")));
    });

  const r = await runMocked(t, () => scrapePlayerHtml("SomeGuy", {}));
  assert.equal(r.ok, false);
  assert.equal(r.originDown, true, "the bound must not hide the outage it fired in place of");
  assert.equal(r.body.error, "origin_timeout");
  assert.equal(r.body.state, "ERROR");
});

test("a transport error that is NOT our timeout stays a plain error", async () => {
  globalThis.fetch = async () => { throw new Error("connection reset"); };
  const r = await scrapePlayerHtml("SomeGuy", {});
  assert.equal(r.ok, false);
  assert.equal(r.originDown, undefined);
  assert.equal(r.body.error, "connection reset");
});

// ---- breaker + fallback flow ------------------------------------------------

test("a 522 trips the breaker and is NOT cached: the SAME player then resolves via shmeado", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  stubCaches();
  const env = { STATS_KV: stubKv() };
  const hits = routeFetch([
    ["hypixel.net/player/", () => new Response(ORIGIN_ERROR_PAGE, { status: 522 })],
    ["shmeado.club/player/stats/beepor/", () => new Response(BEEPOR_HTML)],
  ]);
  const w = makeCtx();

  // 1) Hypixel's origin is down. The lookup fails with the precise status, having asked hypixel once.
  const down = await runMocked(t, () => getBedwars("beepor", env, w.ctx, false));
  assert.equal(down.success, false);
  assert.equal(down.error, "http_522");
  assert.equal(hits.hypixel(), 1);
  assert.equal(hits.shmeado(), 0);
  await w.settle();
  assert.equal(await readBlocked(env), true, "an origin outage must trip the global breaker");

  // 2) The same player again. This is the case the negative cache used to swallow: the breaker
  //    is set, but readCached runs first, so a cached http_522 would have been served here and
  //    the fallback would never have been reached.
  const viaFallback = await runMocked(t, () => getBedwars("beepor", env, w.ctx, false));
  await w.settle();
  assert.equal(viaFallback.state, "OK", "the fallback must serve the player the outage denied");
  assert.notEqual(viaFallback.cached, true, "a breaker-tripping failure must not be cached");
  assert.equal(viaFallback.bedwarsLevel, 29, "the star rides only fallback-served bodies");
  assert.equal(hits.shmeado(), 1);
  assert.equal(hits.hypixel(), 1, "zero further hypixel calls once the breaker is set");
});

test("a plain 500 neither trips the breaker nor stops negative caching", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  stubCaches();
  const env = { STATS_KV: stubKv() };
  const hits = routeFetch([
    ["hypixel.net/player/", () => new Response("<html><body>oops</body></html>", { status: 500 })],
    ["shmeado.club/", () => new Response(BEEPOR_HTML)],
  ]);
  const w = makeCtx();

  const first = await runMocked(t, () => getBedwars("beepor", env, w.ctx, false));
  assert.equal(first.error, "http_500");
  assert.equal(hits.hypixel(), 1);
  await w.settle();
  assert.equal(await readBlocked(env), false, "a 500 is not evidence the origin is down");

  // Still negatively cached for 90 s: the repeat is served from cache, hypixel untouched, and
  // shmeado is never consulted because the breaker was never set.
  const repeat = await runMocked(t, () => getBedwars("beepor", env, w.ctx, false));
  await w.settle();
  assert.equal(repeat.cached, true);
  assert.equal(repeat.error, "http_500");
  assert.equal(hits.hypixel(), 1);
  assert.equal(hits.shmeado(), 0);
});
