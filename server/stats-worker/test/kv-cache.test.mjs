/**
 * Unit tests for cache.js's two-tier stats cache with stubbed caches.default + KV: KV hits
 * after an L1 miss, L1 re-warm with the REMAINING ttl (never the full one), the
 * positives-and-NICKED-only KV write policy, embedded-timestamp staleness, and no-KV
 * degradation. The L1 stub honors max-age against the (mockable) clock so expiry behavior
 * is observable, not structural.
 */
import test from "node:test";
import assert from "node:assert/strict";
import {
  readCached, writeCached, CACHE_TTL_SEC, NEG_TTL_SEC, NICKED_TTL_SEC,
} from "../src/cache.js";

const OK_BODY = { success: true, state: "OK", displayName: "OkGuy", wins: 10 };
const NICKED_BODY = { success: false, state: "NICKED", displayName: "NickGuy" };
const NEG_BODY = { success: false, state: "ERROR", displayName: "SadGuy", error: "parse_failed" };

/** L1 stub that respects cache-control max-age against Date.now(), so mocked-clock ticks
 *  really expire entries. */
function stubCaches() {
  const store = new Map();
  globalThis.caches = {
    default: {
      async match(req) {
        const e = store.get(req.url);
        if (!e) return undefined;
        if (Date.now() >= e.expiresAt) { store.delete(req.url); return undefined; }
        return new Response(e.body, { headers: { "content-type": "application/json" } });
      },
      async put(req, res) {
        const m = (res.headers.get("cache-control") || "").match(/max-age=(\d+)/);
        store.set(req.url, {
          body: await res.text(),
          maxAge: m ? Number(m[1]) : 0,
          expiresAt: Date.now() + (m ? Number(m[1]) : 0) * 1000,
        });
      },
    },
  };
  return store;
}

function makeKv(seed = {}) {
  const store = new Map(Object.entries(seed));
  return {
    store,
    gets: 0,
    async get(k, type) {
      this.gets++;
      const v = store.has(k) ? store.get(k) : null;
      if (v == null) return null;
      return type === "json" ? JSON.parse(v) : v;
    },
    async put(k, v) { store.set(k, v); },
    async delete(k) { store.delete(k); },
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

const kvRecord = (body, ttlSec, ageSec = 0) =>
  JSON.stringify({ writtenAt: Date.now() - ageSec * 1000, ttlSec, body });

test("KV hit after an L1 miss returns the body and re-warms L1 via ctx.waitUntil", async () => {
  const l1 = stubCaches();
  const kv = makeKv({ "bw:v2:okguy": kvRecord(OK_BODY, CACHE_TTL_SEC) });
  const w = makeCtx();
  const body = await readCached("OkGuy", { STATS_KV: kv }, w.ctx);
  assert.deepEqual(body, OK_BODY);
  await w.settle();
  assert.equal(l1.size, 1, "the KV hit must have re-warmed L1");
  // The re-warm makes the NEXT read an L1 hit: no second KV round trip.
  const before = kv.gets;
  const again = await readCached("OkGuy", { STATS_KV: kv }, w.ctx);
  assert.deepEqual(again, OK_BODY);
  assert.equal(kv.gets, before);
});

test("L1 re-warm uses the REMAINING ttl: an L1 read after the original expiry misses", async (t) => {
  const l1 = stubCaches();
  t.mock.timers.enable({ apis: ["Date"], now: 1_800_000_000_000 });
  // A record written 860 s ago has 40 s left. Full-TTL re-warm here was the rev 2 defect:
  // a near-expiry record served for nearly another 15 min from a freshly warmed colo.
  const kv = makeKv({ "bw:v2:okguy": kvRecord(OK_BODY, CACHE_TTL_SEC, 860) });
  const w = makeCtx();
  const body = await readCached("OkGuy", { STATS_KV: kv }, w.ctx);
  assert.deepEqual(body, OK_BODY);
  await w.settle();
  const entry = [...l1.values()][0];
  assert.ok(entry.maxAge <= 40, `re-warmed with max-age=${entry.maxAge}, not the remainder`);
  assert.ok(entry.maxAge >= 30, "the remainder above the floor must survive intact");

  // Past the ORIGINAL expiry both tiers must miss: L1 aged out, KV embedded-age discards.
  t.mock.timers.tick(41_000);
  assert.equal(await readCached("OkGuy", { STATS_KV: kv }, w.ctx), null);
});

test("a KV record with under 30 s remaining is treated as a miss, never re-warmed", async () => {
  const l1 = stubCaches();
  const kv = makeKv({ "bw:v2:okguy": kvRecord(OK_BODY, CACHE_TTL_SEC, CACHE_TTL_SEC - 20) });
  const w = makeCtx();
  assert.equal(await readCached("OkGuy", { STATS_KV: kv }, w.ctx), null);
  await w.settle();
  assert.equal(l1.size, 0);
});

test("a stale embedded timestamp (older than its TTL) is a miss even when KV still serves it", async () => {
  // KV expiration is not exact; the embedded write timestamp is the authority.
  stubCaches();
  const kv = makeKv({ "bw:v2:okguy": kvRecord(OK_BODY, CACHE_TTL_SEC, CACHE_TTL_SEC + 60) });
  const w = makeCtx();
  assert.equal(await readCached("OkGuy", { STATS_KV: kv }, w.ctx), null);
});

test("positive parses and NICKED are written to KV; transient negatives stay L1-only", async () => {
  stubCaches();
  const kv = makeKv();
  const w = makeCtx();
  const env = { STATS_KV: kv };
  writeCached("OkGuy", OK_BODY, env, w.ctx); // default = CACHE_TTL_SEC
  writeCached("NickGuy", NICKED_BODY, env, w.ctx, NICKED_TTL_SEC);
  writeCached("SadGuy", NEG_BODY, env, w.ctx, NEG_TTL_SEC);
  await w.settle();
  assert.deepEqual([...kv.store.keys()].sort(), ["bw:v2:nickguy", "bw:v2:okguy"]);
  // The negative still round-trips through L1 (today's behavior, untouched).
  assert.deepEqual(await readCached("SadGuy", env, w.ctx), NEG_BODY);
  // And what KV holds reads back as written, embedded timestamp intact.
  const rec = JSON.parse(kv.store.get("bw:v2:nickguy"));
  assert.equal(rec.ttlSec, NICKED_TTL_SEC);
  assert.ok(Number.isFinite(rec.writtenAt));
  assert.deepEqual(rec.body, NICKED_BODY);
});

test("no STATS_KV binding: exactly the L1-only behavior, and no throw anywhere", async () => {
  stubCaches();
  const w = makeCtx();
  const env = {};
  assert.equal(await readCached("OkGuy", env, w.ctx), null);
  writeCached("OkGuy", OK_BODY, env, w.ctx);
  await w.settle();
  assert.deepEqual(await readCached("OkGuy", env, w.ctx), OK_BODY);
});

test("a KV read failure degrades to a miss instead of failing the lookup", async () => {
  stubCaches();
  const kv = makeKv();
  kv.get = async () => { throw new Error("kv down"); };
  const w = makeCtx();
  assert.equal(await readCached("OkGuy", { STATS_KV: kv }, w.ctx), null);
});
