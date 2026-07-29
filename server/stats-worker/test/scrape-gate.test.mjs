/**
 * Tests for scrape.js's shared origin gate: every hypixel origin start in the isolate - the batch
 * counter pool and the single route's counter scrape - must claim a slot from ONE timeline, and a
 * 429 anywhere must throttle all of them. The gate has two lanes, so the priority tests also pin
 * that HIGH work REORDERS the timeline without ever adding grants to it.
 *
 * Time is mocked (Date + setTimeout) so the gate's sleeps are instant; the mocked fetch records
 * Date.now() at each call, and those timestamps ARE the assertions.
 *
 * NO RESET DOOR: the gate is module-level state with no exported reset, but every field is
 * compared against `now`, so a test only has to start its mocked clock beyond anything a previous
 * test could have claimed. `nextClock()` hands out clock origins 10 minutes apart for exactly that
 * (the longest state a test can leave behind is the 60 s elevation window).
 */
import test from "node:test";
import assert from "node:assert/strict";
import { getBedwars, streamBedwarsBatch, LANE_HIGH, LANE_LOW } from "../src/scrape.js";

const MIN_SPACING_MS = 620; // SPACING_MS - the tightest spacing the gate ever grants at
const BACKOFF_SPACING_MS = 1300; // ELEVATED_SPACING_MS while elevated
const BACKOFF_BASE_MS = 1200; // ORIGIN_BACKOFF_BASE_MS, the pause a single 429 buys
const HIGH_STREAK_MAX = 3; // the low lane's fairness floor: >= 1 slot in 4
// Slack the PICK_QUANTUM_MS (25 ms) re-check adds per contended grant, plus the 10 ms tick step.
// It can only ever make the gate SLOWER, so every timing bound below is an upper bound.
const EPS_MS = 200;

let clockBase = 1_800_000_000_000;
const nextClock = () => (clockBase += 10 * 60 * 1000);

// Minimal page shape: a page with no Bedwars section at all (-> NICKED).
const NICKED_HTML = "<html><body><h1>Some profile without stats</h1></body></html>";

const env = {}; // no STATS_KV: L1-only, and the stub below misses every time

function stubCaches() {
  globalThis.caches = { default: { async match() { return undefined; }, async put() {} } };
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

/** Install a fetch that records { url, at } per call and answers via `reply(url, callNo)`. */
function recordFetch(reply) {
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push({ url: String(url), at: Date.now() });
    return reply(String(url), calls.length);
  };
  return calls;
}

/**
 * Drive `work()` to completion against the mocked clock: flush microtasks, tick, repeat. The gate
 * sleeps on the mocked setTimeout, so nothing progresses without the ticks.
 */
async function runMocked(t, work, maxMs = 90_000, stepMs = 10) {
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

/**
 * Tick the mocked clock until done(), calling onTick(elapsedMs) BEFORE each tick so a test can
 * inject arrivals at staggered mocked times. Same flush-then-tick shape as runMocked.
 */
async function drive(t, done, onTick, maxMs = 90_000, stepMs = 10) {
  for (let elapsed = 0; elapsed < maxMs; elapsed += stepMs) {
    await new Promise((r) => setImmediate(r));
    if (done()) break;
    if (onTick) onTick(elapsed);
    t.mock.timers.tick(stepMs);
  }
  await new Promise((r) => setImmediate(r));
}

/** Count outstanding work so drive() knows when a mixed-lane scenario has fully settled. */
function tracker() {
  const all = [];
  let outstanding = 0;
  return {
    add(p) {
      outstanding++;
      all.push(Promise.resolve(p).then(() => { outstanding--; }, () => { outstanding--; }));
    },
    idle: () => outstanding === 0,
    settle: () => Promise.all(all),
  };
}

/** Run a batch to completion, draining the NDJSON stream so writes never block on backpressure. */
function batchWork(names, ctxWrap, lane = LANE_LOW) {
  return async () => {
    const res = streamBedwarsBatch(names, env, ctxWrap.ctx, undefined, undefined, lane);
    const text = res.text();
    await ctxWrap.settle();
    await text;
  };
}

function gaps(calls) {
  return calls.slice(1).map((c, i) => c.at - calls[i].at);
}

test.beforeEach(() => {
  stubCaches();
  globalThis.fetch = async () => { throw new Error("unexpected fetch"); };
});

test("a single lookup and a batch share one timeline: no two origin starts closer than the spacing", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const calls = recordFetch(() => new Response(NICKED_HTML));
  const w = makeCtx();
  await runMocked(t, async () => {
    const batch = batchWork(["Alpha", "Bravo", "Charlie"], w)();
    // HIGH lane on purpose: the invariant must hold ACROSS lanes, which is strictly stronger.
    const single = getBedwars("Solo", env, w.ctx, false, LANE_HIGH);
    await Promise.all([batch, single]);
  });
  assert.equal(calls.length, 4, "3 batch misses + 1 single, all cold");
  for (const g of gaps(calls)) {
    assert.ok(g >= MIN_SPACING_MS, `origin starts ${g} ms apart, under the ${MIN_SPACING_MS} ms claim`);
  }
});

test("a 429 on the SINGLE route delays and re-spaces a later batch claim", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  // Only the single route's first attempt is rate-limited; everything after it succeeds.
  const calls = recordFetch((_url, n) => (n === 1 ? new Response("", { status: 429 }) : new Response(NICKED_HTML)));
  const w = makeCtx();
  await runMocked(t, async () => { await getBedwars("Golf", env, w.ctx, false); });
  assert.equal(calls.length, 2, "the single retried its own 429");
  assert.ok(calls[1].at - calls[0].at >= BACKOFF_BASE_MS, "the 429 pauses the whole gate, not just this caller");

  await runMocked(t, batchWork(["Hotel", "India"], w));
  assert.equal(calls.length, 4);
  for (const g of gaps(calls).slice(1)) {
    assert.ok(g >= BACKOFF_SPACING_MS, `${g} ms apart: a batch claim must inherit the elevated spacing`);
  }
});

test("the rate invariant survives mixed priority: 18 grants, one timeline, nothing doubled up", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const calls = recordFetch(() => new Response(NICKED_HTML));
  const w = makeCtx();
  const track = tracker();
  track.add(batchWork(Array.from({ length: 12 }, (_, i) => `Bulk${i}`), w, LANE_LOW)());
  let launched = 0;
  await drive(
    t,
    () => launched === 6 && track.idle(),
    (elapsed) => {
      if (launched < 6 && elapsed % 800 === 0) {
        track.add(getBedwars(`Rush${launched++}`, env, w.ctx, false, LANE_HIGH));
      }
    }
  );
  await track.settle();
  assert.equal(calls.length, 18, "12 batch misses + 6 high singles, all cold, none duplicated");
  for (const g of gaps(calls)) {
    assert.ok(g >= MIN_SPACING_MS, `origin starts ${g} ms apart, under the ${MIN_SPACING_MS} ms spacing`);
  }
  // Catches two waiters both believing they won: the gap check alone can miss a duplicate that
  // happens to land later, but the total span cannot shrink below 17 spacings.
  const span = calls[calls.length - 1].at - calls[0].at;
  assert.ok(span >= 17 * MIN_SPACING_MS, `18 grants spanned only ${span} ms`);
});

test("the high lane preempts: a single arriving mid-batch is served next, not last", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const calls = recordFetch(() => new Response(NICKED_HTML));
  const w = makeCtx();
  const track = tracker();
  track.add(batchWork(Array.from({ length: 8 }, (_, i) => `Queued${i}`), w, LANE_LOW)());
  let arrivedAt = null;
  await drive(
    t,
    () => arrivedAt !== null && track.idle(),
    () => {
      if (arrivedAt === null && calls.length >= 2) {
        arrivedAt = Date.now();
        track.add(getBedwars("Jumper", env, w.ctx, false, LANE_HIGH));
      }
    }
  );
  await track.settle();
  assert.equal(calls.length, 9);
  const high = calls.find((c) => c.url.includes("Jumper"));
  assert.ok(high, "the high single reached the origin");
  const wait = high.at - arrivedAt;
  assert.ok(
    wait <= 2 * MIN_SPACING_MS + EPS_MS,
    `high waited ${wait} ms; nothing is reserved, so one spacing plus one deferral is the bound`
  );
  // Real reordering, not luck: most of the already-queued batch must still be behind it.
  const after = calls.filter((c) => c.url.includes("Queued") && c.at > high.at).length;
  assert.ok(after >= 5, `only ${after} batch starts followed the high single`);
});

test("the low lane is not starved: the fairness floor holds under a saturated high lane", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const calls = recordFetch(() => new Response(NICKED_HTML));
  const w = makeCtx();
  const track = tracker();
  track.add(batchWork(Array.from({ length: 6 }, (_, i) => `Slow${i}`), w, LANE_LOW)());
  let launched = 0;
  await drive(
    t,
    () => launched === 20 && track.idle(),
    (elapsed) => {
      // Every 300 ms: faster than the gate can serve, so the high lane stays saturated throughout.
      if (launched < 20 && elapsed % 300 === 0) {
        track.add(getBedwars(`Fast${launched++}`, env, w.ctx, false, LANE_HIGH));
      }
    }
  );
  await track.settle();
  assert.equal(calls.length, 26, "the low batch finished inside the window rather than being starved");
  const low = calls.filter((c) => c.url.includes("Slow"));
  assert.equal(low.length, 6);
  assert.ok(
    low.length >= Math.floor(calls.length / (HIGH_STREAK_MAX + 1)) - 1,
    `${low.length} low grants out of ${calls.length} is below the 1-in-${HIGH_STREAK_MAX + 1} floor`
  );
  // Pins the FLOOR rather than an average, so an off-by-one in the streak counter fails here.
  for (const g of gaps(low)) {
    assert.ok(
      g <= (HIGH_STREAK_MAX + 1) * MIN_SPACING_MS + EPS_MS,
      `${g} ms between low grants: the low lane slipped past its 1-in-${HIGH_STREAK_MAX + 1} slot`
    );
  }
});

test("429 elevation covers the high lane too: a later high single inherits the elevated spacing", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const calls = recordFetch((_url, n) => (n === 1 ? new Response("", { status: 429 }) : new Response(NICKED_HTML)));
  const w = makeCtx();
  const track = tracker();
  track.add(batchWork(["Nova", "Orbit"], w, LANE_LOW)());
  let sent = false;
  await drive(
    t,
    () => sent && track.idle(),
    () => {
      if (!sent && calls.length >= 2) {
        sent = true;
        track.add(getBedwars("Priority", env, w.ctx, false, LANE_HIGH));
      }
    }
  );
  await track.settle();
  const i = calls.findIndex((c) => c.url.includes("Priority"));
  assert.ok(i > 0, "the high single ran after the 429");
  const g = calls[i].at - calls[i - 1].at;
  assert.ok(g >= BACKOFF_SPACING_MS, `${g} ms after the previous grant: the high lane must be elevated too`);
});

test("post-429 elevation decays: after 60 s the gate is back to per-claim spacing", async (t) => {
  t.mock.timers.enable({ apis: ["Date", "setTimeout"], now: nextClock() });
  const calls = recordFetch((_url, n) => (n === 1 ? new Response("", { status: 429 }) : new Response(NICKED_HTML)));
  const w = makeCtx();
  await runMocked(t, async () => { await getBedwars("Juliett", env, w.ctx, false); });
  assert.equal(calls.length, 2);

  t.mock.timers.tick(61_000); // past the elevation window, with nothing in flight
  const before = calls.length;
  await runMocked(t, batchWork(["Kilo", "Lima", "Mike"], w));
  const after = gaps(calls.slice(before));
  assert.equal(calls.length - before, 3);
  for (const g of after) {
    assert.ok(g >= MIN_SPACING_MS, `${g} ms apart, under the ${MIN_SPACING_MS} ms claim`);
    assert.ok(g < BACKOFF_SPACING_MS, `${g} ms apart: the elevation should have decayed`);
  }
});
