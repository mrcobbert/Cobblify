/**
 * Fork + single-flight tests for the shmeado fallback, in the scrape-gate mocked-clock style
 * (stubbed fetch/caches/KV, no sockets - sandbox-safe): while the hypixel breaker is unset every
 * lookup scrapes hypixel and shmeado sees zero traffic; once a challenge page trips the breaker,
 * lookups go to shmeado (zero hypixel traffic) and carry the star. Failure policy: a shmeado
 * transport failure returns today's exact blocked body UNCACHED (a repeat re-fetches shmeado,
 * hypixel stays frozen, the breaker is untouched); a structural parse_failed is a 90 s L1-only
 * negative. Concurrent lookups of one cold player collapse to one upstream fetch.
 *
 * State discipline: caches/KV stubs are per-test (so each test trips its own breaker), while the
 * module-level origin gate + shmeado pacer persist - nextClock() starts each test's mocked clock
 * 10 minutes later, past anything a previous test could have claimed.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { getBedwars, streamBedwarsBatch, LANE_HIGH } from "../src/scrape.js";

const BEEPOR_HTML = readFileSync(
  fileURLToPath(new URL("./fixtures/shmeado-ok-beepor.html", import.meta.url)), "utf8");

let clockBase = 1_900_000_000_000; // offset from scrape-gate's origins; same 10-minute stepping
const nextClock = () => (clockBase += 10 * 60 * 1000);

// Hypixel forum page carrying EXACTLY beepor's six counters, so the healthy-path parse and the
// fallback-path parse of the same player can be compared field for field.
const HYP_OK_HTML = [
  '<html><body><div id="stats-content-bedwars"><table>',
  "<tr><td>Wins</td><td>61</td></tr>",
  "<tr><td>Losses</td><td>508</td></tr>",
  "<tr><td>Kills</td><td>772</td></tr>",
  "<tr><td>Deaths</td><td>1,762</td></tr>",
  "<tr><td>Final Kills</td><td>155</td></tr>",
  "<tr><td>Final Deaths</td><td>563</td></tr>",
  "</table></div></body></html>",
].join("\n");
const HYP_CHALLENGE_HTML = "<html><head><title>Just a moment...</title></head><body></body></html>";

const BLOCKED_BODY = (player) => ({
  success: false, state: "ERROR", displayName: player, error: "blocked_by_cloudflare",
});

// ---- stubs -----------------------------------------------------------------

function stubCaches() {
  const store = new Map(); // request url -> stored Response
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

/** Fetch stub routing by URL substring; records every hit. */
function routeFetch(routes) {
  const hits = [];
  globalThis.fetch = async (url) => {
    const u = String(url);
    hits.push(u);
    for (const [pattern, reply] of routes) {
      if (u.includes(pattern)) return reply(u);
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

/** Drive `work()` to completion against the mocked clock (see scrape-gate.test.mjs). */
async function runMocked(t, work, maxMs = 120_000, stepMs = 10) {
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

/** Trip the hypixel breaker the real way: one challenge-page scrape, writes settled. */
async function tripBreaker(t, env, w) {
  const body = await runMocked(t, () => getBedwars("Tripper", env, w.ctx, false));
  assert.equal(body.error, "blocked_by_cloudflare");
  await w.settle();
}

// ---- tests -----------------------------------------------------------------

test("healthy origin: hypixel scraped, zero shmeado calls, no star; blocked: the reverse, star present, same counters", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  stubCaches();
  const kv = stubKv();
  const env = { STATS_KV: kv };
  const hits = routeFetch([
    ["hypixel.net/player/Tripper", () => new Response(HYP_CHALLENGE_HTML)],
    ["hypixel.net/player/", () => new Response(HYP_OK_HTML)],
    ["shmeado.club/player/stats/beepor/", () => new Response(BEEPOR_HTML)],
  ]);
  const w = makeCtx();

  // Healthy: the lookup scrapes hypixel; shmeado sees nothing; no star on the wire.
  const healthy = await runMocked(t, () => getBedwars("HypTwin", env, w.ctx, false));
  assert.equal(healthy.state, "OK");
  assert.equal(hits.hypixel(), 1);
  assert.equal(hits.shmeado(), 0);
  assert.ok(!("bedwarsLevel" in healthy), "hypixel-path bodies never carry the star");
  await w.settle();

  await tripBreaker(t, env, w);
  const frozen = hits.hypixel();

  // Blocked: the same-name fixture player resolves via shmeado with the star, hypixel frozen.
  const fallback = await runMocked(t, () => getBedwars("beepor", env, w.ctx, false));
  await w.settle();
  assert.equal(hits.hypixel(), frozen, "zero hypixel calls while blocked");
  assert.equal(hits.shmeado(), 1);
  assert.equal(fallback.state, "OK");
  assert.equal(fallback.displayName, "beepor");
  assert.equal(fallback.rank, "mvp_plus");
  assert.equal(fallback.bedwarsLevel, 29);
  // Counter parity: the fallback parse equals the hypixel parse of the equivalent player.
  assert.deepEqual(fallback.overall, healthy.overall);

  // The fallback result is durably cached (L1+KV): a repeat hits neither source.
  const repeat = await runMocked(t, () => getBedwars("beepor", env, w.ctx, false));
  assert.equal(repeat.cached, true);
  assert.equal(repeat.bedwarsLevel, 29);
  assert.equal(hits.shmeado(), 1);
  assert.equal(hits.hypixel(), frozen);
  assert.ok(kv.puts.includes("bw:v2:beepor"), "fallback success reaches KV like a primary parse");
});

test("shmeado 500 while blocked: exact blocked body, uncached, hypixel and breaker untouched", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const l1 = stubCaches();
  const kv = stubKv();
  const env = { STATS_KV: kv };
  const hits = routeFetch([
    ["hypixel.net/player/Tripper", () => new Response(HYP_CHALLENGE_HTML)],
    ["shmeado.club/player/stats/FailGuy/", () => new Response("oops", { status: 500 })],
  ]);
  const w = makeCtx();
  await tripBreaker(t, env, w);
  const frozen = hits.hypixel();

  const body = await runMocked(t, () => getBedwars("FailGuy", env, w.ctx, false));
  await w.settle();
  // Byte-identical to today's blocked short-circuit: exactly these four keys, no httpStatus.
  assert.deepStrictEqual(body, BLOCKED_BODY("FailGuy"));

  // Uncached: a repeat re-fetches shmeado; hypixel stays frozen; nothing landed in L1 or KV.
  const again = await runMocked(t, () => getBedwars("FailGuy", env, w.ctx, false));
  await w.settle();
  assert.deepStrictEqual(again, BLOCKED_BODY("FailGuy"));
  assert.equal(hits.shmeado(), 2, "a NEW shmeado hit proves the failure body was not cached");
  assert.equal(hits.hypixel(), frozen, "the shmeado failure never touches hypixel or its breaker");
  assert.ok(![...l1.keys()].some((k) => k.includes("failguy")), "no L1 entry for the player");
  assert.ok(!kv.puts.some((k) => k.includes("failguy")), "no KV entry for the player");
});

test("structural parse_failed while blocked: 90 s L1-only negative, never KV", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const l1 = stubCaches();
  const kv = stubKv();
  const env = { STATS_KV: kv };
  const hits = routeFetch([
    ["hypixel.net/player/Tripper", () => new Response(HYP_CHALLENGE_HTML)],
    ["shmeado.club/player/stats/DriftGuy/", () => new Response("<html><body>redesigned</body></html>")],
  ]);
  const w = makeCtx();
  await tripBreaker(t, env, w);

  const body = await runMocked(t, () => getBedwars("DriftGuy", env, w.ctx, false));
  await w.settle();
  assert.equal(body.error, "parse_failed");
  const entry = [...l1.entries()].find(([k]) => k.includes("driftguy"));
  assert.ok(entry, "the negative is L1-cached");
  assert.match(entry[1].headers.get("cache-control"), /max-age=90\b/, "NEG_TTL, not the success TTL");
  assert.ok(!kv.puts.some((k) => k.includes("driftguy")), "transient negatives never reach KV");

  // Within the TTL the cached negative is served: one shmeado hit total.
  const again = await runMocked(t, () => getBedwars("DriftGuy", env, w.ctx, false));
  assert.equal(again.error, "parse_failed");
  assert.equal(again.cached, true);
  assert.equal(hits.shmeado(), 1);
});

test("single-flight: a batch fetch and a concurrent single of the same cold player share one upstream call", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  stubCaches();
  const env = {};
  const hits = routeFetch([["hypixel.net/player/", () => new Response(HYP_OK_HTML)]]);
  const w = makeCtx();

  let single;
  await runMocked(t, async () => {
    const res = streamBedwarsBatch(["Dup"], env, w.ctx, undefined, undefined);
    const text = res.text();
    // The client fallback-single race (B3): same player, while the batch fetch is in flight.
    single = getBedwars("Dup", env, w.ctx, false, LANE_HIGH);
    await w.settle();
    await text;
    await single;
  });
  const body = await single;
  assert.equal(body.state, "OK");
  assert.equal(hits.hypixel(), 1, "both lookups collapsed onto one origin fetch");
});
