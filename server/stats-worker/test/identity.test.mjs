/**
 * Cross-identity isolation and per-requester settlement (plan D4), at two levels:
 *
 *  - module level: handleKeySet touches ONLY the caller's KV slot; lookups send the
 *    caller's own key upstream; the URCHIN_KEY/SERAPH_KEY secrets cover the OWNER only.
 *  - ROUTE level over the streaming /bedwars/batch path (the real scrape.js -> provider
 *    identity handoff): identities A and B concurrent for the same UUID share ONE upstream
 *    call; when A's key fails (401 / 429 / transient, both providers) A gets its usual
 *    failure shape, B gets the stale fallback (urchin, flagless) or full omission - never
 *    someone else's terminal flag - and only A's backoff/disabled state arms.
 *
 * Every scenario uses fresh tokens (=> fresh identities and per-identity isolate state) and
 * fresh uuids/names, so the module-level maps can never leak between scenarios.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../src/worker.js";
import { tagsForUuids as urchinTagsForUuids, handleKeySet as urchinKeySet } from "../src/urchin.js";
import { tagsForUuids as seraphTagsForUuids, handleKeySet as seraphKeySet } from "../src/seraph.js";

const idOf = (t) => createHash("sha256").update(t).digest("hex").slice(0, 16);
const authOf = (t, isOwner = false) => ({ identity: idOf(t), isOwner });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function until(cond, ms = 4000) {
  const start = Date.now();
  while (!cond()) {
    if (Date.now() - start > ms) throw new Error("timed out waiting for condition");
    await sleep(5);
  }
}

function stubCaches(seed = {}) {
  const store = new Map(Object.entries(seed));
  globalThis.caches = {
    default: {
      async match(req) {
        const b = store.get(req.url);
        return b === undefined ? undefined : new Response(b, { headers: { "content-type": "application/json" } });
      },
      async put(req, res) { store.set(req.url, await res.text()); },
    },
  };
  return store;
}

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

function makeCtx() {
  const pending = [];
  return {
    ctx: { waitUntil(p) { pending.push(Promise.resolve(p).catch(() => {})); } },
    async settle() { while (pending.length) await Promise.all(pending.splice(0)); },
  };
}

const ctx = { waitUntil() {} };
const cachedBody = (name) => JSON.stringify({ success: true, state: "OK", displayName: name, wins: 1 });
const l1Url = (name) => `https://bedwarsqol.internal/cache/bedwars/v2/${name.toLowerCase()}`;
const staleUrchinEntry = () =>
  JSON.stringify({ tags: [{ type: "sniper", reason: "s", addedOn: 1, expiresAt: null }], fetchedAt: Date.now() - 7 * 3600 * 1000 });

let scenarioN = 0;
/** Unique tokens (=> identities), uuids and names per scenario: no cross-test state leaks. */
function freshScenario() {
  const n = ++scenarioN;
  return {
    tokenA: `IdentA_${n}_0123456789abcdef`,
    tokenB: `IdentB_${n}_0123456789abcdef`,
    uuid: (0x100 + n).toString(16).padStart(32, "0"),
    uuid2: (0x9100 + n).toString(16).padStart(32, "0"),
    name: `DupGuy${n}`,
    name2: `SoloGuy${n}`,
  };
}

// ---- module level ----------------------------------------------------------

test.beforeEach(() => {
  stubCaches(); // readEntry consults caches.default before KV
  globalThis.fetch = async () => { throw new Error("unexpected fetch"); };
});

test("urchin/seraph handleKeySet mutate ONLY the caller's slot (owner slot byte-identical)", async () => {
  for (const [keySet, prefix] of [[urchinKeySet, "urchin"], [seraphKeySet, "seraph"]]) {
    const OWNER_T = `Owner_${prefix}_0123456789ab`;
    const FRIEND_T = `Friend_${prefix}_0123456789a`;
    const ownerSlot = `${prefix}:cfg:key:${idOf(OWNER_T)}`;
    const friendSlot = `${prefix}:cfg:key:${idOf(FRIEND_T)}`;
    const kv = makeKv({ [ownerSlot]: "owner-key-original", [friendSlot]: "friend-key-original" });
    const env = { STATS_KV: kv };
    const post = (key) => new Request("https://x/key", { method: "POST", body: JSON.stringify({ key }) });

    const set = await keySet(post("friend-key-updated!"), env, ctx, authOf(FRIEND_T));
    assert.equal(set.status, 200);
    assert.equal(kv.store.get(friendSlot), "friend-key-updated!");
    assert.equal(kv.store.get(ownerSlot), "owner-key-original", `${prefix}: owner slot must be untouched`);

    const clear = await keySet(post(null), env, ctx, authOf(FRIEND_T));
    assert.equal(clear.status, 200);
    assert.equal(kv.store.has(friendSlot), false);
    assert.equal(kv.store.get(ownerSlot), "owner-key-original", `${prefix}: clear must not touch the owner`);
  }
});

test("lookups send the CALLER's key upstream (headers captured per provider)", async () => {
  const FRIEND_T = "Friend_lookup_0123456789";
  const friendAuth = authOf(FRIEND_T);
  const captured = [];
  globalThis.fetch = async (url, init) => {
    captured.push({ url: String(url), headers: (init && init.headers) || {} });
    return new Response(JSON.stringify({ players: {} }), { headers: { "content-type": "application/json" } });
  };
  const uKv = makeKv({ [`urchin:cfg:key:${friendAuth.identity}`]: "FRIEND-URCHIN-KEY-01" });
  await urchinTagsForUuids(["a".repeat(32)], { STATS_KV: uKv }, ctx, friendAuth);
  assert.equal(captured.length, 1);
  assert.equal(captured[0].headers["X-API-Key"], "FRIEND-URCHIN-KEY-01");

  globalThis.fetch = async (url, init) => {
    captured.push({ url: String(url), headers: (init && init.headers) || {} });
    return new Response(JSON.stringify({ data: {} }), { headers: { "content-type": "application/json" } });
  };
  const sKv = makeKv({ [`seraph:cfg:key:${friendAuth.identity}`]: "FRIEND-SERAPH-KEY-01" });
  await seraphTagsForUuids(["b".repeat(32)], { STATS_KV: sKv }, ctx, friendAuth);
  assert.equal(captured.length, 2);
  assert.equal(captured[1].headers.key, "FRIEND-SERAPH-KEY-01");
});

test("URCHIN_KEY/SERAPH_KEY secrets cover the OWNER identity only; 409 only for the owner", async () => {
  const OWNER_T = "Owner_secret_0123456789a";
  const FRIEND_T = "Friend_secret_0123456789";
  const post = (key) => new Request("https://x/key", { method: "POST", body: JSON.stringify({ key }) });

  // 409 key_managed_by_secret is the OWNER's own conflict; a friend still manages its slot.
  const env = { STATS_KV: makeKv(), URCHIN_KEY: "owner-secret-key-0001" };
  assert.equal((await urchinKeySet(post("any-key-0123456789"), env, ctx, authOf(OWNER_T, true))).status, 409);
  assert.equal((await urchinKeySet(post("friend-key-0123456789"), env, ctx, authOf(FRIEND_T))).status, 200);
  const sEnv = { STATS_KV: makeKv(), SERAPH_KEY: "owner-secret-key-0002" };
  assert.equal((await seraphKeySet(post("any-key-0123456789"), sEnv, ctx, authOf(OWNER_T, true))).status, 409);
  assert.equal((await seraphKeySet(post("friend-key-0123456789"), sEnv, ctx, authOf(FRIEND_T))).status, 200);

  // Key selection: the owner's lookup uses the secret; the (slotless) friend has NO key.
  const captured = [];
  globalThis.fetch = async (url, init) => {
    captured.push((init && init.headers) || {});
    return new Response(JSON.stringify({ players: {} }), { headers: { "content-type": "application/json" } });
  };
  const lookupEnv = { STATS_KV: makeKv(), URCHIN_KEY: "owner-secret-key-0001" };
  await urchinTagsForUuids(["c".repeat(32)], lookupEnv, ctx, authOf(OWNER_T, true));
  assert.equal(captured.length, 1);
  assert.equal(captured[0]["X-API-Key"], "owner-secret-key-0001");
  const friendRes = await urchinTagsForUuids(["d".repeat(32)], lookupEnv, ctx, authOf(FRIEND_T));
  assert.equal(captured.length, 1, "a slotless friend must make no upstream call off the owner's secret");
  assert.equal(friendRes.get("d".repeat(32)).state, "unavailable");
});

// ---- route level: streaming /bedwars/batch concurrency ---------------------

/**
 * Drive two REAL worker.fetch batch requests concurrently for the same name/uuid: A's
 * upstream call is held at a gate until B has joined the in-flight entry, then released
 * with the scenario's failure. Returns per-response merged fields plus the KV mock.
 */
async function concurrentBatch({ provider, seedKv = {}, upstream }) {
  const s = freshScenario();
  const idA = idOf(s.tokenA);
  const idB = idOf(s.tokenB);
  const kv = makeKv({
    [`${provider}:cfg:key:${idA}`]: "KEY-A-0123456789",
    [`${provider}:cfg:key:${idB}`]: "KEY-B-0123456789",
    ...Object.fromEntries(Object.entries(seedKv).map(([k, v]) => [k.replace("<uuid>", s.uuid), v])),
  });
  stubCaches({ [l1Url(s.name)]: cachedBody(s.name), [l1Url(s.name2)]: cachedBody(s.name2) });
  const env = {
    STATS_TOKEN: `${s.tokenA},${s.tokenB}`,
    STATS_KV: kv,
    HYPIXEL_BASE: "https://hy.test",
    URCHIN_BASE: "https://coral.test",
    SERAPH_BASE: "https://seraph.test",
  };
  let release;
  const gate = new Promise((r) => { release = r; });
  const calls = [];
  globalThis.fetch = async (url, init) => {
    const u = String(url);
    if (u.startsWith("https://coral.test") || u.startsWith("https://seraph.test")) {
      const h = (init && init.headers) || {};
      const call = { url: u, key: h["X-API-Key"] || h.key };
      calls.push(call);
      await gate;
      return upstream(call, s);
    }
    return new Response("<html>fixture</html>", { headers: { "content-type": "text/html" } });
  };

  const optHeader = provider === "urchin" ? "X-BWQOL-Urchin" : "X-BWQOL-Seraph";
  const batchReq = (token) =>
    new Request(`https://w.test/bedwars/batch?names=${s.name}&uuids=${s.uuid}`, {
      headers: { "X-BedwarsQol-Token": token, [optHeader]: "1" },
    });
  const wA = makeCtx();
  const wB = makeCtx();
  const resA = await worker.fetch(batchReq(s.tokenA), env, wA.ctx);
  const textA = resA.text();
  await until(() => calls.length >= 1); // A's keyed upstream call is in flight
  assert.equal(calls[0].key, "KEY-A-0123456789", "A must be the executing identity");
  const resB = await worker.fetch(batchReq(s.tokenB), env, wB.ctx);
  const textB = resB.text();
  await sleep(30); // let B's lookup join the in-flight entry rather than fetch
  release();
  const [rawA, rawB] = await Promise.all([textA, textB]);
  await wA.settle();
  await wB.settle();
  assert.equal(calls.length, 1, "one upstream call total: B piggybacked A's in-flight batch");

  const merge = (raw) => {
    const fields = {};
    for (const line of raw.trim().split("\n").filter(Boolean).map((l) => JSON.parse(l))) {
      if (line.name === s.name) Object.assign(fields, line);
    }
    return fields;
  };
  return { s, idA, idB, kv, calls, env, a: merge(rawA), b: merge(rawB) };
}

/** After a scenario: B's own single lookup must still go upstream, with B's key. */
async function assertBProceeds({ s, env, calls }, provider) {
  const okBody = provider === "urchin" ? { players: {} } : { data: {} };
  globalThis.fetch = async (url, init) => {
    const u = String(url);
    if (u.startsWith("https://coral.test") || u.startsWith("https://seraph.test")) {
      const h = (init && init.headers) || {};
      calls.push({ url: u, key: h["X-API-Key"] || h.key });
      return new Response(JSON.stringify(okBody), { headers: { "content-type": "application/json" } });
    }
    return new Response("<html>fixture</html>", { headers: { "content-type": "text/html" } });
  };
  const optHeader = provider === "urchin" ? "X-BWQOL-Urchin" : "X-BWQOL-Seraph";
  const w = makeCtx();
  const res = await worker.fetch(
    new Request(`https://w.test/bedwars/${s.name2}?uuid=${s.uuid2}`, {
      headers: { "X-BedwarsQol-Token": s.tokenB, [optHeader]: "1" },
    }),
    env,
    w.ctx
  );
  assert.equal(res.status, 200);
  await res.json();
  await w.settle();
  const last = calls[calls.length - 1];
  assert.equal(last.key, "KEY-B-0123456789", "B's next lookup must proceed with B's own key");
}

const URCHIN_ABSENT = ["urchin", "urchinChecked", "urchinUnavailable", "urchinNotFound"];
const SERAPH_ABSENT = ["seraph", "seraphChecked", "seraphUnavailable", "seraphNotFound"];

test("urchin batch, A's key 401s: both omit (no stale), disable arms for A only, B unaffected", async () => {
  const r = await concurrentBatch({ provider: "urchin", upstream: () => new Response("", { status: 401 }) });
  for (const f of URCHIN_ABSENT) {
    assert.ok(!(f in r.a), `A (executing, no stale) must omit ${f}`);
    assert.ok(!(f in r.b), `B (piggybacking, no stale) must omit ${f}`);
  }
  // One rejection is not yet a disable (WAF-blip tolerance); a second 401 under A arms it.
  globalThis.fetch = async () => { await sleep(0); return new Response("", { status: 401 }); };
  const w = makeCtx();
  await (await worker.fetch(
    new Request(`https://w.test/bedwars/${r.s.name2}?uuid=${r.s.uuid2}`, {
      headers: { "X-BedwarsQol-Token": r.s.tokenA, "X-BWQOL-Urchin": "1" },
    }), r.env, w.ctx)).json();
  await w.settle();
  assert.ok(r.kv.store.has(`urchin:cfg:disabled:${r.idA}`), "two 401s under A must disable A");
  assert.equal(r.kv.store.has(`urchin:cfg:disabled:${r.idB}`), false, "B's slot must stay untouched");
  await assertBProceeds(r, "urchin");
});

test("urchin batch, A 401s WITH a stale entry: both get stale tags, both terminal flags absent", async () => {
  const r = await concurrentBatch({
    provider: "urchin",
    seedKv: { "urchin:v2:<uuid>": staleUrchinEntry() },
    upstream: () => new Response("", { status: 401 }),
  });
  for (const [who, fields] of [["A", r.a], ["B", r.b]]) {
    // Two separate assertions per D4: the stale tags survive AND the flags are absent.
    assert.equal(fields.urchin.tags[0].type, "sniper", `${who} must still see the stale tag`);
    assert.ok(!("urchinChecked" in fields), `${who} must not be marked checked`);
    assert.ok(!("urchinUnavailable" in fields), `${who} must not be marked unavailable`);
  }
});

test("urchin batch, A's key 429s: backoff arms for A only; both responses stay retryable", async () => {
  const r = await concurrentBatch({ provider: "urchin", upstream: () => new Response("", { status: 429 }) });
  for (const f of URCHIN_ABSENT) {
    assert.ok(!(f in r.a) && !(f in r.b), `${f} must be omitted for both`);
  }
  assert.ok(r.kv.store.has(`urchin:cfg:backoff:${r.idA}`), "A's 429 must arm A's backoff");
  assert.equal(r.kv.store.has(`urchin:cfg:backoff:${r.idB}`), false, "B must not inherit A's backoff");
  await assertBProceeds(r, "urchin");
});

test("urchin batch, transient error under A: both omit, no backoff/disabled state for anyone", async () => {
  const r = await concurrentBatch({ provider: "urchin", upstream: () => { throw new Error("boom"); } });
  for (const f of URCHIN_ABSENT) {
    assert.ok(!(f in r.a) && !(f in r.b), `${f} must be omitted for both`);
  }
  for (const k of r.kv.store.keys()) {
    assert.ok(!k.includes(":cfg:backoff:") && !k.includes(":cfg:disabled:"), `unexpected state key ${k}`);
  }
  await assertBProceeds(r, "urchin");
});

test("urchin batch, upstream 5xx under A: both omit, no backoff/disabled state for anyone", async () => {
  const r = await concurrentBatch({ provider: "urchin", upstream: () => new Response("{}", { status: 502 }) });
  for (const f of URCHIN_ABSENT) {
    assert.ok(!(f in r.a) && !(f in r.b), `${f} must be omitted for both (5xx is transient)`);
  }
  for (const k of r.kv.store.keys()) {
    assert.ok(!k.includes(":cfg:backoff:") && !k.includes(":cfg:disabled:"), `unexpected state key ${k}`);
  }
  await assertBProceeds(r, "urchin");
});

test("seraph batch, A's key 401s: A gets its usual unavailable, B omits ALL seraph fields", async () => {
  const r = await concurrentBatch({ provider: "seraph", upstream: () => new Response("", { status: 401 }) });
  assert.equal(r.a.seraphUnavailable, true, "the executing identity keeps today's shape");
  for (const f of SERAPH_ABSENT) assert.ok(!(f in r.b), `B (piggybacking) must omit ${f}`);
  assert.ok(r.kv.store.has(`seraph:cfg:disabled:${r.idA}`), "A's rejection must disable A");
  assert.equal(r.kv.store.has(`seraph:cfg:disabled:${r.idB}`), false, "B's slot must stay untouched");
  await assertBProceeds(r, "seraph");
});

test("seraph batch, A's key 429s: A unavailable, B omits, backoff arms for A only", async () => {
  const r = await concurrentBatch({ provider: "seraph", upstream: () => new Response("", { status: 429 }) });
  assert.equal(r.a.seraphUnavailable, true);
  for (const f of SERAPH_ABSENT) assert.ok(!(f in r.b), `B must omit ${f}`);
  assert.ok(r.kv.store.has(`seraph:cfg:backoff:${r.idA}`));
  assert.equal(r.kv.store.has(`seraph:cfg:backoff:${r.idB}`), false);
  await assertBProceeds(r, "seraph");
});

test("seraph batch, transient error under A: both omit (Seraph has no stale fallback)", async () => {
  const r = await concurrentBatch({ provider: "seraph", upstream: () => { throw new Error("boom"); } });
  for (const f of SERAPH_ABSENT) {
    assert.ok(!(f in r.a), `A must omit ${f} (transient errors are retryable even executing)`);
    assert.ok(!(f in r.b), `B must omit ${f}`);
  }
  for (const k of r.kv.store.keys()) {
    assert.ok(!k.includes(":cfg:backoff:") && !k.includes(":cfg:disabled:"), `unexpected state key ${k}`);
  }
  await assertBProceeds(r, "seraph");
});

test("seraph batch, upstream 5xx under A: both omit, no backoff/disabled state for anyone", async () => {
  const r = await concurrentBatch({ provider: "seraph", upstream: () => new Response("{}", { status: 502 }) });
  for (const f of SERAPH_ABSENT) {
    assert.ok(!(f in r.a), `A must omit ${f} (5xx is transient even executing)`);
    assert.ok(!(f in r.b), `B must omit ${f}`);
  }
  for (const k of r.kv.store.keys()) {
    assert.ok(!k.includes(":cfg:backoff:") && !k.includes(":cfg:disabled:"), `unexpected state key ${k}`);
  }
  await assertBProceeds(r, "seraph");
});

test("route-level success sharing: one upstream call, BOTH identities get the result", async () => {
  const r = await concurrentBatch({
    provider: "urchin",
    upstream: (_call, s) => new Response(
      JSON.stringify({ players: { [s.uuid]: [{ tag_type: "sniper", reason: "q", added_on: 5 }] } }),
      { headers: { "content-type": "application/json" } }
    ),
  });
  for (const [who, fields] of [["A", r.a], ["B", r.b]]) {
    assert.equal(fields.urchinChecked, true, `${who} must share the concluded result`);
    assert.equal(fields.urchin.tags[0].type, "sniper");
    assert.equal(fields.urchinUuid, r.s.uuid);
  }
});
