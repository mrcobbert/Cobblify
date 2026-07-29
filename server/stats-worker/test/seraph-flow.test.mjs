/**
 * Flow tests for seraph.js's upstream-failure circuit breaker, with stubbed KV and a mocked
 * fetch whose call count IS the assertion: Seraph has no cache (§7d), so during an outage the
 * only thing that can stop one keyed GET per player per lobby is the breaker.
 *
 * Cloned from the urchin-flow.test.mjs patterns (resetIsolate via the key route, Date mock
 * timers, fetch call counts).
 */
import test from "node:test";
import assert from "node:assert/strict";
import { tagsForUuids, resultFields, handleKeySet } from "../src/seraph.js";

const uuidN = (n) => n.toString(16).padStart(32, "0");
const KEY = "k".repeat(16);

function makeKv(seed = {}) {
  const store = new Map(Object.entries(seed));
  return {
    store,
    async get(k, type) {
      const v = store.has(k) ? store.get(k) : null;
      if (v == null) return null;
      return type === "json" ? JSON.parse(v) : v;
    },
    async put(k, v) { store.set(k, v); },
    async delete(k) { store.delete(k); },
  };
}

const ctx = { waitUntil() {} };
const keyed = () => ({ STATS_KV: makeKv({ "seraph:cfg:key": KEY }) });

/**
 * seraph.js keeps its backoff / key-rejected / breaker state in module-level variables, which
 * persist for the whole test FILE. handleKeySet clearing them is the only exported door to that
 * state; without this, every test declared after a trip runs vacuously (the breaker
 * short-circuits before any fetch, so the assertions would pass for the wrong reason).
 */
async function resetIsolate() {
  const req = new Request("https://x/seraph/key", {
    method: "POST",
    headers: { "X-BedwarsQol-Token": "t" },
    body: JSON.stringify({ key: null }),
  });
  await handleKeySet(req, { STATS_TOKEN: "t", STATS_KV: makeKv() }, ctx);
}

test.beforeEach(async () => {
  globalThis.fetch = async () => { throw new Error("unexpected fetch"); };
  await resetIsolate();
});

test("breaker: 3 consecutive 5xx suppress the next lookup, with ZERO KV state", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 503 }); };
  const env = keyed();
  for (let i = 1; i <= 3; i++) await tagsForUuids([uuidN(i)], env, ctx);
  assert.equal(fetches, 3);

  const res = await tagsForUuids([uuidN(4)], env, ctx);
  assert.equal(fetches, 3, "the 4th lookup must not reach upstream");
  // Fails OPEN in shape too: a suppressed lookup is the ordinary transient failure, so the uuid
  // is OMITTED (client retries) rather than resolved-unavailable (which sticks forever).
  assert.equal(res.has(uuidN(4)), false);
  assert.deepEqual(resultFields(res.get(uuidN(4)), uuidN(4)), {});
  // The breaker is three module numbers - it may touch no KV key at all (§7d: no result data
  // is stored anywhere either).
  assert.deepEqual([...env.STATS_KV.store.keys()], ["seraph:cfg:key"]);
});

test("breaker: any successful round trip resets the streak (never latches)", async () => {
  let fetches = 0;
  let fail = true;
  globalThis.fetch = async () => {
    fetches++;
    return fail
      ? new Response("{}", { status: 502 })
      : new Response(JSON.stringify({ data: {} }), { headers: { "content-type": "application/json" } });
  };
  const env = keyed();
  await tagsForUuids([uuidN(11)], env, ctx);
  await tagsForUuids([uuidN(12)], env, ctx);
  fail = false;
  await tagsForUuids([uuidN(13)], env, ctx); // success clears the 2-failure streak
  fail = true;
  await tagsForUuids([uuidN(14)], env, ctx);
  await tagsForUuids([uuidN(15)], env, ctx);
  assert.equal(fetches, 5, "a reset streak must not trip at the 3rd cumulative failure");
});

test("breaker: the failure streak decays, so blips far apart never trip it", async (t) => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 502 }); };
  const env = keyed();
  t.mock.timers.enable({ apis: ["Date"], now: Date.now() });
  await tagsForUuids([uuidN(21)], env, ctx);
  await tagsForUuids([uuidN(22)], env, ctx);
  t.mock.timers.tick(61_000); // older than FAIL_COOLDOWN_MS: not the same incident
  await tagsForUuids([uuidN(23)], env, ctx);
  assert.equal(fetches, 3);
  await tagsForUuids([uuidN(24)], env, ctx);
  assert.equal(fetches, 4, "a decayed streak must not trip on the 3rd cumulative failure");
});

test("breaker: the trip expires after the cooldown and lookups resume", async (t) => {
  let fetches = 0;
  let fail = true;
  globalThis.fetch = async () => {
    fetches++;
    return fail
      ? new Response("{}", { status: 503 })
      : new Response(JSON.stringify({ data: { blacklist: { tagged: true, report_type: "cheater" } } }), {
          headers: { "content-type": "application/json" },
        });
  };
  const env = keyed();
  t.mock.timers.enable({ apis: ["Date"], now: Date.now() });
  for (let i = 31; i <= 33; i++) await tagsForUuids([uuidN(i)], env, ctx);
  assert.equal(fetches, 3);
  await tagsForUuids([uuidN(34)], env, ctx);
  assert.equal(fetches, 3, "tripped");

  fail = false;
  t.mock.timers.tick(61_000); // past FAIL_COOLDOWN_MS
  const res = await tagsForUuids([uuidN(35)], env, ctx);
  assert.equal(fetches, 4, "an expired breaker must let traffic through again");
  assert.equal(res.get(uuidN(35)).state, "ok");
  assert.equal(res.get(uuidN(35)).tags[0].kind, "blacklist");
});

test("breaker: a concurrent batch of mixed successes and failures does not trip", async () => {
  // The fan-out is bounded but concurrent, so a partially-failing batch interleaves failures with
  // successes; only a genuine outage (nothing succeeding) may suppress traffic.
  let fetches = 0;
  globalThis.fetch = async () => {
    const n = ++fetches;
    return n % 2 === 0
      ? new Response("{}", { status: 503 })
      : new Response(JSON.stringify({ data: {} }), { headers: { "content-type": "application/json" } });
  };
  const env = keyed();
  const many = Array.from({ length: 8 }, (_, i) => uuidN(41 + i));
  const res = await tagsForUuids(many, env, ctx);
  assert.equal(fetches, 8, "every uuid in the batch must reach upstream");
  // Half concluded, half omitted (transient) - and the breaker must still be open afterwards.
  assert.equal([...res.values()].filter((r) => r.state === "ok").length, 4);
  await tagsForUuids([uuidN(60)], env, ctx);
  assert.equal(fetches, 9, "a mixed batch is not an outage: the next lookup must still fetch");
});
