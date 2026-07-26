/**
 * Flow tests for urchin.js with stubbed KV/edge-cache/fetch: keyless behavior, fresh-cache
 * reuse, stale fallbacks, backoff arming, and malformed-expiry rejection - the failure
 * matrix the route tests can't exercise deterministically.
 */
import test from "node:test";
import assert from "node:assert/strict";
import {
  tagsForUuids, tagsForName, mapTags, resultFields, handleKeySet, urchinAllowed, urchinCapable,
  inflightSize, resetInflight,
} from "../src/urchin.js";

const UUID = "069a79f444e94726a5befca90e38aaf5";
const uuidN = (n) => n.toString(16).padStart(32, "0");

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

function stubCaches() {
  globalThis.caches = { default: { async match() { return undefined; }, async put() {} } };
}

function env(kv, extra = {}) {
  return { STATS_KV: kv, ...extra };
}
const ctx = { waitUntil() {} };

function freshEntry(tags, ageMs = 0) {
  return JSON.stringify({ tags, fetchedAt: Date.now() - ageMs });
}

/**
 * urchin.js keeps its backoff / key-rejected / breaker state in module-level variables, which
 * persist for the whole test FILE. handleKeySet clearing them is the only exported door to
 * that state. Without this reset every test declared after the first 429 or 401 runs
 * vacuously: isBlocked short-circuits before any fetch, so the assertions pass for the wrong
 * reason (verified - it is why the pre-existing "429 triggers isolate-local backoff" case was
 * passing with zero upstream calls).
 */
async function resetIsolate() {
  const req = new Request("https://x/urchin/key", {
    method: "POST",
    headers: { "X-BedwarsQol-Token": "t" },
    body: JSON.stringify({ key: null }),
  });
  await handleKeySet(req, { STATS_TOKEN: "t", STATS_KV: makeKv() }, ctx);
}

test.beforeEach(async () => {
  stubCaches();
  globalThis.fetch = async () => { throw new Error("unexpected fetch"); };
  resetInflight(); // the poisoning test deliberately abandons an entry
  await resetIsolate();
});

test("keyless: cached tags are NEVER shown (clear must fully disable display)", async () => {
  const kv = makeKv({ [`urchin:v2:${UUID}`]: freshEntry([{ type: "sniper", reason: "", addedOn: 1, expiresAt: null }]) });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  const r = res.get(UUID);
  assert.equal(r.state, "unavailable");
  assert.equal(r.tags.length, 0);
});

test("keyless: no cache -> unavailable-empty, zero upstream fetches", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; throw new Error("no"); };
  const res = await tagsForUuids([UUID], env(makeKv()), ctx);
  assert.equal(res.get(UUID).state, "unavailable");
  assert.equal(fetches, 0);
});

test("expiry-after-cache: an expired stored tag ENDS freshness and forces a refetch", async () => {
  let fetches = 0;
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ players: { [UUID]: [{ tag_type: "caution", reason: "new", added_on: 2 }] } }), {
      headers: { "content-type": "application/json" },
    });
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (...a) => { fetches++; return origFetch(...a); };
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    [`urchin:v2:${UUID}`]: freshEntry([{ type: "sniper", reason: "", addedOn: 1, expiresAt: Date.now() - 5 }]),
  });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  const r = res.get(UUID);
  assert.equal(fetches, 1); // entry is young but its tag expired -> no longer fresh
  assert.equal(r.state, "ok");
  assert.deepEqual(r.tags.map((x) => x.type), ["caution"]);
});

test("batch omits a uuid: no stale -> NO map entry; stale -> tags with NO flag (both retryable)", async () => {
  // Absent from the v3 batch response is "unknown", not "clean" - and it is TRANSIENT, so
  // neither shape may resolve the entry. With nothing stale to show the uuid must be MISSING
  // from the map (resultFields emits {}); with stale tags it must emit them WITHOUT
  // urchinChecked/urchinUnavailable, so they display and the client still retries. Reporting
  // "unavailable" for the stale case pinned the entry resolved forever purely because it
  // happened to have old data.
  globalThis.fetch = async () => new Response(JSON.stringify({ players: {} }), {
    headers: { "content-type": "application/json" },
  });
  const bare = await tagsForUuids([UUID], env(makeKv({ "urchin:cfg:key": "k".repeat(16) })), ctx);
  assert.equal(bare.has(UUID), false);

  const withStale = await tagsForUuids(
    [UUID],
    env(makeKv({
      "urchin:cfg:key": "k".repeat(16),
      [`urchin:v2:${UUID}`]: freshEntry([{ type: "sniper", reason: "s", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
    })),
    ctx
  );
  const fields = resultFields(withStale.get(UUID), UUID);
  assert.equal(fields.urchinUnavailable, undefined); // sticky client-side - never for a transient miss
  assert.equal(fields.urchinChecked, undefined);
  assert.deepEqual(fields.urchin.tags.map((t) => t.type), ["sniper"]); // shown anyway
});

test("mapTags: v3 tag_type wins over the legacy `type` spelling", () => {
  const tags = mapTags([{ tag_type: "sniper", type: "info", reason: "x", added_on: 1 }], Date.now());
  // Legacy-first precedence would read "info" and drop the tag entirely.
  assert.deepEqual(tags.map((t) => t.type), ["sniper"]);
});

test("stale + transient 5xx -> stale tags with NO flag (retryable); 429 sets backoff flag", async () => {
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    [`urchin:v2:${UUID}`]: freshEntry([{ type: "caution", reason: "", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
  });
  globalThis.fetch = async () => new Response("{}", { status: 502 });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  // A 5xx is transient by definition, so the stale tags must ship flagless: displayed, and the
  // client keeps retrying. urchinUnavailable would end retries for the entry's whole lifetime.
  const fields = resultFields(res.get(UUID), UUID);
  assert.equal(fields.urchinUnavailable, undefined);
  assert.equal(fields.urchinChecked, undefined);
  assert.equal(fields.urchin.tags[0].type, "caution");

  globalThis.fetch = async () => new Response("{}", { status: 429 });
  const kv2 = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  await tagsForUuids([UUID], env(kv2), ctx);
  assert.ok(kv2.store.has("urchin:cfg:backoff"));
});

test("manual: fresh alias cache reuse spends no Coral request", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; throw new Error("no"); };
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    "urchin:name:v1:someguy": UUID,
    [`urchin:v2:${UUID}`]: freshEntry([{ type: "sniper", reason: "q", addedOn: 1, expiresAt: null }]),
  });
  const r = await tagsForName("SomeGuy", env(kv), ctx);
  assert.equal(r.state, "ok");
  assert.equal(r.tags[0].type, "sniper");
  assert.equal(fetches, 0);
});

test("manual: keyless is fully off; backoff falls back to stale alias data", async () => {
  const seed = {
    "urchin:name:v1:someguy": UUID,
    [`urchin:v2:${UUID}`]: freshEntry([{ type: "caution", reason: "", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
  };
  // Keyless: fully off - not even stale tags (clear contract).
  const keyless = await tagsForName("SomeGuy", env(makeKv(seed)), ctx);
  assert.equal(keyless.state, "unavailable");
  assert.equal(keyless.tags.length, 0);

  const backed = await tagsForName(
    "SomeGuy",
    env(makeKv({ ...seed, "urchin:cfg:key": "k".repeat(16), "urchin:cfg:backoff": String(Date.now()) })),
    ctx
  );
  assert.equal(backed.state, "unavailable");
  assert.equal(backed.tags.length, 1);
});

test("manual: timeout with stale -> unavailable+stale; without stale -> unavailable-empty", async () => {
  globalThis.fetch = async () => { throw new Error("timeout"); };
  const withStale = await tagsForName(
    "SomeGuy",
    env(makeKv({
      "urchin:cfg:key": "k".repeat(16),
      "urchin:name:v1:someguy": UUID,
      [`urchin:v2:${UUID}`]: freshEntry([{ type: "sniper", reason: "", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
    })),
    ctx
  );
  assert.equal(withStale.state, "unavailable");
  assert.equal(withStale.stale, true);
  assert.equal(withStale.tags.length, 1);

  const without = await tagsForName("Fresh", env(makeKv({ "urchin:cfg:key": "k".repeat(16) })), ctx);
  assert.equal(without.state, "unavailable");
  assert.equal(without.tags.length, 0);
});

test("mapTags: present-but-invalid or negative expires_at drops the tag", () => {
  const now = Date.now();
  const tags = mapTags(
    [
      { tag_type: "sniper", reason: "a", added_on: 1, expires_at: "soon" },
      { tag_type: "caution", reason: "b", added_on: 1, expires_at: -5 },
      { tag_type: "closet_cheater", reason: "c", added_on: 1, expires_at: null },
      { tag_type: "blatant_cheater", reason: "d", added_on: 1 },
    ],
    now
  );
  assert.deepEqual(tags.map((t) => t.type), ["closet_cheater", "blatant_cheater"]);
});

test("a service notice is never a tag: dropped on ingest, real tags survive", () => {
  const now = Date.now();
  const notice =
    "Notice for the developer of this service: the Urchin API is deprecated and shuts down " +
    "on July 31. Blacklist tags are no longer being updated. Migrate to the new API - " +
    "docs: https://api.urchin.gg";
  // Live v2 shape 2026-07-25: the notice rode ALONGSIDE real tags, so the real one must
  // survive. Kept as ingest-side defense against any future upstream nag (the v2->v3 cache
  // namespace bump orphaned every entry that could have stored one, so there is no read-side
  // filter to test).
  const tags = mapTags(
    [
      { tag_type: "caution", reason: notice, added_on: 1_784_000_000_000 },
      { tag_type: "sniper", reason: "max toggled scaffold", added_on: 1_783_000_000_000 },
    ],
    now
  );
  assert.deepEqual(tags.map((t) => t.type), ["sniper"]);
  assert.equal(tags[0].reason, "max toggled scaffold");
});

test("sanitize (via mapTags): ALM and deprecated bidi controls are stripped", () => {
  const alm = String.fromCharCode(0x061c);
  const dep = String.fromCharCode(0x206a);
  const tags = mapTags([{ tag_type: "sniper", reason: "a" + alm + "b" + dep + "c", added_on: 1 }], Date.now());
  assert.equal(tags[0].reason, "a b c");
});

test("no-KV capability: allowed but not capable; key route 503s", async () => {
  const req = new Request("https://x/urchin/key", {
    method: "POST",
    headers: { "X-BedwarsQol-Token": "t", "X-BWQOL-Urchin": "1" },
    body: JSON.stringify({ key: "k".repeat(16) }),
  });
  const envNoKv = { STATS_TOKEN: "t" };
  assert.equal(urchinAllowed(req, envNoKv), true); // auth is separate from capability
  assert.equal(urchinCapable(envNoKv), false);
  const res = await handleKeySet(req, envNoKv, ctx);
  assert.equal(res.status, 503);
});

test("an unreadable block state fails OPEN - a KV blip must not mark players clean", async () => {
  // PINNED BEHAVIOUR CHANGED. This used to assert fail-CLOSED: one failed KV *read* armed a
  // 5 min isolate backoff and returned urchinUnavailable for every uuid in the request, which
  // is sticky client-side - so a momentary KV blip marked real cheaters clean for the cache
  // entry's whole lifetime, with no log and nothing in KV to inspect. Failing open costs at
  // most one Coral call during a live backoff, answered by a 429 that arms the reactive
  // control we already trust.
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 429 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const origGet = kv.get.bind(kv);
  kv.get = async (k, type) => {
    if (k === "urchin:cfg:backoff" || k === "urchin:cfg:disabled") throw new Error("kv down");
    return origGet(k, type);
  };
  const first = await tagsForUuids([UUID], env(kv), ctx);
  assert.equal(fetches, 1, "the call must still be attempted");
  // And the 429 that comes back is transient with nothing stale to show: omitted, retryable.
  assert.equal(first.has(UUID), false);
  assert.deepEqual(resultFields(first.get(UUID), UUID), {});
});

test("blocked (KV backoff): stale ships flagless; nothing stale ships nothing at all", async () => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; throw new Error("no"); };
  const bare = uuidN(41);
  const kv = makeKv({
    "urchin:cfg:key": "k".repeat(16),
    "urchin:cfg:backoff": String(Date.now()),
    [`urchin:v2:${UUID}`]: freshEntry([{ type: "sniper", reason: "s", addedOn: 1, expiresAt: null }], 7 * 3600 * 1000),
  });
  const res = await tagsForUuids([UUID, bare], env(kv), ctx);
  assert.equal(fetches, 0); // the block still suppresses upstream traffic
  // A backoff/disable is TRANSIENT, so neither uuid may be reported as resolved.
  const stale = resultFields(res.get(UUID), UUID);
  assert.equal(stale.urchinUnavailable, undefined);
  assert.equal(stale.urchinChecked, undefined);
  assert.deepEqual(stale.urchin.tags.map((t) => t.type), ["sniper"]);
  assert.equal(res.has(bare), false);
  assert.deepEqual(resultFields(res.get(bare), bare), {});
});

test("429 triggers isolate-local backoff even if the KV write is lost", async () => {
  // Fresh module state per test file is not available (module-level isolate flags), so this
  // asserts the observable contract: after a 429, an immediate second lookup makes no call.
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 429 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const origPut = kv.put.bind(kv);
  kv.put = async (k, v) => { if (k === "urchin:cfg:backoff") throw new Error("lost"); return origPut(k, v); };
  await tagsForUuids(["11111111222233334444555566667778"], env(kv), ctx);
  const before = fetches;
  await tagsForUuids(["11111111222233334444555566667779"], env(kv), ctx);
  assert.equal(fetches, before); // isolate-local backoff blocks the second call
});

test("breaker: 3 consecutive non-429 failures suppress the next call, with ZERO KV state", async () => {
  // 502/503/timeouts arm neither the 429 backoff nor the 401 disable and are never cached, so
  // without this every lobby re-issues a fresh POST /v3/players for the whole outage.
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 503 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  for (let i = 1; i <= 3; i++) await tagsForUuids([uuidN(i)], env(kv), ctx);
  assert.equal(fetches, 3);

  const res = await tagsForUuids([uuidN(4)], env(kv), ctx);
  assert.equal(fetches, 3, "the 4th call must not reach upstream");
  // Fails OPEN in shape too: a suppressed call is the ordinary transient failure, so the uuid
  // is OMITTED (client retries) rather than resolved-unavailable (which sticks forever).
  assert.equal(res.has(uuidN(4)), false);
  // The regression this must never repeat: the deleted reserveBudget wrote one hot KV key per
  // lookup. The breaker is two module numbers - it may touch no KV key at all.
  assert.deepEqual([...kv.store.keys()], ["urchin:cfg:key"]);
});

test("a 404 is upstream health ONLY on the name route; on the batch route it is a failure", async () => {
  // POST /v3/players cannot answer "no such player" - a 404 there means the endpoint moved or
  // the path is wrong, a live risk against an API versioned 0.1.0. Scoring it as health called
  // breakerSuccess on every call, so the breaker was actively RESET each time and could never
  // engage: a 100 %-failure, full-rate, zero-signal loop where every lookup goes upstream,
  // every one comes back retryable and nothing ever caches.
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response(JSON.stringify({ error: "not found" }), { status: 404 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  for (let i = 61; i <= 63; i++) await tagsForUuids([uuidN(i)], env(kv), ctx);
  assert.equal(fetches, 3);
  const res = await tagsForUuids([uuidN(64)], env(kv), ctx);
  assert.equal(fetches, 3, "3 batch 404s are an outage: the breaker must have tripped");
  assert.equal(res.has(uuidN(64)), false); // and never "checked"/"notfound"
  assert.equal(kv.store.has(`urchin:v2:${uuidN(61)}`), false, "a 404 must cache nothing");

  // The name route is the one place a 404 IS the answer, and it still proves the key works.
  await resetIsolate();
  const named = await tagsForName("Ghost", env(makeKv({ "urchin:cfg:key": "k".repeat(16) })), ctx);
  assert.equal(named.state, "notfound");
});

test("in-flight: a canceled request cannot poison a uuid for the isolate's lifetime", { timeout: 10_000 }, async (t) => {
  // workerd can cancel a request mid-flight (the single-player route awaits tagsForUuids
  // WITHOUT ctx.waitUntil, and the Java client hangs up at 15 s), so the owner's `finally`
  // never runs and its in-flight entry outlives the request. Identity-matched deletion alone
  // leaves that uuid claimed for hours: later lookups await a promise nothing will settle.
  let fetches = 0;
  let hang = true;
  globalThis.fetch = async () => {
    fetches++;
    if (hang) return new Promise(() => {}); // the canceled owner's round trip, never settled
    return new Response(JSON.stringify({ players: { [UUID]: [{ tag_type: "sniper", reason: "q", added_on: 5 }] } }), {
      headers: { "content-type": "application/json" },
    });
  };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const abandoned = tagsForUuids([UUID], env(kv), ctx); // deliberately never awaited
  abandoned.catch(() => {});
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(fetches, 1);
  assert.equal(inflightSize(), 1, "non-vacuous: the uuid really is claimed");

  hang = false;
  // Past INFLIGHT_TTL_MS: the orphan must read as absent so the lookup self-heals.
  t.mock.timers.enable({ apis: ["Date"], now: Date.now() + 31_000 });
  const res = await tagsForUuids([UUID], env(kv), ctx);
  assert.equal(fetches, 2, "a stale in-flight entry must not swallow a later lookup");
  assert.equal(res.get(UUID).tags[0].type, "sniper");
});

test("breaker: the failure streak decays, so blips far apart never trip it", async (t) => {
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("{}", { status: 502 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  t.mock.timers.enable({ apis: ["Date"], now: Date.now() });
  await tagsForUuids([uuidN(71)], env(kv), ctx);
  await tagsForUuids([uuidN(72)], env(kv), ctx);
  t.mock.timers.tick(61_000); // older than FAIL_COOLDOWN_MS: not the same incident
  await tagsForUuids([uuidN(73)], env(kv), ctx);
  assert.equal(fetches, 3);
  await tagsForUuids([uuidN(74)], env(kv), ctx);
  assert.equal(fetches, 4, "a decayed streak must not trip on the 3rd cumulative failure");
});

test("breaker: any successful round trip resets the streak (never latches)", async () => {
  let fetches = 0;
  let fail = true;
  globalThis.fetch = async () => {
    fetches++;
    return fail
      ? new Response("{}", { status: 502 })
      : new Response(JSON.stringify({ players: {} }), { headers: { "content-type": "application/json" } });
  };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  await tagsForUuids([uuidN(11)], env(kv), ctx);
  await tagsForUuids([uuidN(12)], env(kv), ctx);
  fail = false;
  await tagsForUuids([uuidN(13)], env(kv), ctx); // success clears the 2-failure streak
  fail = true;
  await tagsForUuids([uuidN(14)], env(kv), ctx);
  await tagsForUuids([uuidN(15)], env(kv), ctx);
  assert.equal(fetches, 5, "a reset streak must not trip at the 3rd cumulative failure");
});

test("a trailing slash on URCHIN_BASE cannot turn every player into urchinNotFound", async () => {
  // "https://api.urchin.gg/" + "/v3/players" = a doubled slash, which Coral answers 404 -
  // i.e. a config typo would silently report "player not found" for EVERY player.
  const urls = [];
  globalThis.fetch = async (u) => {
    urls.push(String(u));
    return new Response(JSON.stringify({ players: {} }), { headers: { "content-type": "application/json" } });
  };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  await tagsForUuids([UUID], env(kv, { URCHIN_BASE: "https://coral.test//" }), ctx);
  await tagsForName("SomeGuy", env(kv, { URCHIN_BASE: "https://coral.test/" }), ctx);
  assert.equal(urls[0], "https://coral.test/v3/players");
  assert.equal(urls[1], "https://coral.test/v3/player/tags?player=SomeGuy");
});

test("a null / non-array tag list is treated as absent, never cached as a clean player", async () => {
  let fetches = 0;
  const other = uuidN(21);
  globalThis.fetch = async () => {
    fetches++;
    return new Response(JSON.stringify({ players: { [UUID]: null, [other]: "oops" } }), {
      headers: { "content-type": "application/json" },
    });
  };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const res = await tagsForUuids([UUID, other], env(kv), ctx);
  // Present-with-[] means checked-and-clean; present-with-garbage means nothing at all.
  assert.equal(res.has(UUID), false);
  assert.equal(res.has(other), false);
  assert.equal(kv.store.has(`urchin:v2:${UUID}`), false, "malformed data must never be stored as clean");
  await tagsForUuids([UUID, other], env(kv), ctx);
  assert.equal(fetches, 2, "nothing was cached, so the lookup is retried");
});

test("service-notice filter: a genuine report QUOTING the notice text survives", () => {
  // The filter deletes tags silently, so a false positive erases a real cheater report with
  // no trace. Anchoring keeps the true positive (the notice IS the whole reason) while
  // letting a report that merely mentions the wording through.
  const quoting = "bragged in party chat that the Urchin API is deprecated so his tags would vanish";
  const tags = mapTags([{ tag_type: "blatant_cheater", reason: quoting, added_on: 1 }], Date.now());
  assert.equal(tags.length, 1);
  assert.equal(tags[0].reason, quoting);

  const alsoQuoting = "reason copied a notice for the developer of this service into his report";
  assert.equal(mapTags([{ tag_type: "sniper", reason: alsoQuoting, added_on: 1 }], Date.now()).length, 1);
});

test("name route: a 200 whose uuid will not normalize -> checked, no urchinUuid, no cache", async () => {
  // Intended shape, pinned: v3 answers an unknown name with a real 404, so a 2xx here IS the
  // answer and stays "checked" - an unusable uuid costs only the cache write and the
  // provenance join key. (Silently downgrading it to unavailable would hide real tags.)
  globalThis.fetch = async () =>
    new Response(JSON.stringify({ uuid: "not-a-uuid", displayname: "Ghost", tags: [{ tag_type: "sniper", reason: "q", added_on: 5 }] }), {
      headers: { "content-type": "application/json" },
    });
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const r = await tagsForName("Ghost", env(kv), ctx);
  assert.equal(r.state, "ok");
  assert.equal(r.uuid, null);
  assert.deepEqual(resultFields(r, r.uuid), {
    urchinChecked: true,
    urchin: { tags: [{ type: "sniper", reason: "q", addedOn: 5 }] },
  });
  assert.equal(kv.store.has("urchin:name:v1:ghost"), false); // unusable uuid -> no alias
});

test("401: ONE rejection never disables; two in a row arm the SHARED KV flag", async () => {
  // PINNED BEHAVIOUR CHANGED. A single 401/403 used to arm the 1 h cross-isolate disable.
  // api.urchin.gg is behind Cloudflare, so a WAF/bot-management 403 - or a 401 during an
  // upstream deploy - is indistinguishable from a bad key here, and one of them cost an hour
  // of suppressed lookups. Two consecutive rejections is still a bad key; one is noise.
  let fetches = 0;
  globalThis.fetch = async () => { fetches++; return new Response("", { status: 401 }); };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  await tagsForUuids([UUID], env(kv), ctx);
  assert.equal(fetches, 1);
  assert.equal(kv.store.has("urchin:cfg:disabled"), false, "one rejection must not disable");

  await tagsForUuids([uuidN(51)], env(kv), ctx);
  assert.equal(fetches, 2, "a single rejection must not have blocked the retry either");
  assert.ok(kv.store.has("urchin:cfg:disabled"), "the cross-isolate disable must be persisted");

  // Isolate flags cleared = a different isolate: only the KV value can block now. The route
  // test runs in ONE process, where isolateKeyRejectedAt alone would explain what it sees.
  const disabled = kv.store.get("urchin:cfg:disabled");
  await resetIsolate();
  const kv2 = makeKv({ "urchin:cfg:key": "k".repeat(16), "urchin:cfg:disabled": disabled });
  const res = await tagsForUuids([UUID], env(kv2), ctx);
  assert.equal(fetches, 2, "the KV disabled flag alone must stop a fresh isolate");
  assert.equal(res.has(UUID), false); // blocked + no stale -> retryable, not sticky
});

test("401: any successful round trip resets the rejection streak", async () => {
  // Otherwise two WAF 403s an hour apart still add up to a 1 h disable.
  let mode = 401;
  globalThis.fetch = async () =>
    mode === 401
      ? new Response("", { status: 401 })
      : new Response(JSON.stringify({ players: {} }), { headers: { "content-type": "application/json" } });
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  await tagsForUuids([uuidN(52)], env(kv), ctx);
  mode = 200;
  await tagsForUuids([uuidN(53)], env(kv), ctx);
  mode = 401;
  await tagsForUuids([uuidN(54)], env(kv), ctx);
  assert.equal(kv.store.has("urchin:cfg:disabled"), false, "the streak must have been cleared by the success");
});

test("batch cap: at most 100 uuids per upstream request; the remainder stays retryable", async () => {
  const bodies = [];
  globalThis.fetch = async (_u, init) => {
    const sent = JSON.parse(init.body).uuids;
    bodies.push(sent);
    return new Response(JSON.stringify({ players: Object.fromEntries(sent.map((u) => [u, []])) }), {
      headers: { "content-type": "application/json" },
    });
  };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const many = Array.from({ length: 120 }, (_, i) => uuidN(i + 101));
  const res = await tagsForUuids(many, env(kv), ctx);
  assert.equal(bodies.length, 1);
  assert.deepEqual(bodies[0], many.slice(0, 100)); // the only ceiling on one upstream request
  for (const u of many.slice(0, 100)) assert.equal(res.get(u).state, "ok");
  // Over the cap: never sent, so never answered - omitted (retryable), never "clean".
  for (const u of many.slice(100)) assert.equal(res.has(u), false);
});

test("in-flight dedupe: a concurrent lookup piggybacks instead of issuing a second batch", async () => {
  let fetches = 0;
  let release;
  const gate = new Promise((r) => { release = r; });
  globalThis.fetch = async () => {
    fetches++;
    await gate;
    return new Response(JSON.stringify({ players: { [UUID]: [{ tag_type: "sniper", reason: "q", added_on: 5 }] } }), {
      headers: { "content-type": "application/json" },
    });
  };
  const kv = makeKv({ "urchin:cfg:key": "k".repeat(16) });
  const first = tagsForUuids([UUID], env(kv), ctx);
  await new Promise((r) => setTimeout(r, 0));
  const second = tagsForUuids([UUID], env(kv), ctx); // must join the pending batch
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(fetches, 1);
  release();
  const [a, b] = await Promise.all([first, second]);
  assert.equal(fetches, 1);
  assert.equal(a.get(UUID).tags[0].type, "sniper");
  assert.equal(b.get(UUID).tags[0].type, "sniper"); // the piggybacker gets the same answer
});

test("key mutation contract: cleanup failure -> 503 AND key unchanged", async () => {
  const kv = makeKv({ "urchin:cfg:key": "oldkey-0123456789", "urchin:cfg:disabled": String(Date.now()) });
  const origDelete = kv.delete.bind(kv);
  kv.delete = async (k) => { if (k === "urchin:cfg:disabled") throw new Error("kv down"); return origDelete(k); };
  const req = new Request("https://x/urchin/key", {
    method: "POST",
    headers: { "X-BedwarsQol-Token": "t" },
    body: JSON.stringify({ key: null }),
  });
  const res = await handleKeySet(req, { STATS_TOKEN: "t", STATS_KV: kv }, ctx);
  assert.equal(res.status, 503);
  // Failure means NOTHING happened to the key: it is still set.
  assert.equal(kv.store.get("urchin:cfg:key"), "oldkey-0123456789");
});
