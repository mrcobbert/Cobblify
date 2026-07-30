/**
 * Auth matrix for the multi-token STATS_TOKEN list, driving the real worker.fetch handler
 * in-process (stubbed caches.default / KV / fetch): every authenticated route x
 * {owner, friend, second friend, wrong, empty header, whitespace token, duplicate list
 * entries, changed-list revocation}, plus the canonical-parser and identity contracts of
 * authenticate() itself. The open (unset-secret) worker is pinned too: data routes open,
 * provider routes fail-closed.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker, { authenticate } from "../src/worker.js";
import { urchinAllowed } from "../src/urchin.js";
import { seraphAllowed } from "../src/seraph.js";

const OWNER = "OwnerToken_0123456789abcdef";
const FRIEND = "FriendToken_0123456789abcd";
const FRIEND2 = "SecondFriend_0123456789ab";
// Duplicates and empty segments must be dropped by the canonical parser without disturbing
// the order (first SURVIVING entry = owner).
const LIST = ` ${OWNER}, ${FRIEND} ,, ${FRIEND2}, ${FRIEND}`;

const idOf = (token) => createHash("sha256").update(token).digest("hex").slice(0, 16);

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

const OK_PAGE = "<html><body><div id=\"stats-content-bedwars\"></div>ok</body></html>";
const cachedBody = (name) =>
  JSON.stringify({ success: true, state: "OK", displayName: name, wins: 1 });
const l1Url = (name) => `https://bedwarsqol.internal/cache/bedwars/v2/${name.toLowerCase()}`;

function baseEnv(kv, statsToken = LIST) {
  return { STATS_TOKEN: statsToken, STATS_KV: kv, HYPIXEL_BASE: "https://hy.test" };
}

function reqFor(path, { token, optUrchin, optSeraph, method, body } = {}) {
  const headers = {};
  if (token !== undefined) headers["X-BedwarsQol-Token"] = token;
  if (optUrchin) headers["X-BWQOL-Urchin"] = "1";
  if (optSeraph) headers["X-BWQOL-Seraph"] = "1";
  return new Request(`https://w.test${path}`, { method: method || "GET", headers, body });
}

test.beforeEach(() => {
  // Counter lookups are pre-warmed L1 hits so no route ever touches the origin gate.
  stubCaches({
    [l1Url("MatrixGuy")]: cachedBody("MatrixGuy"),
    [l1Url("BatchGuy")]: cachedBody("BatchGuy"),
  });
  globalThis.fetch = async () => new Response(OK_PAGE, { headers: { "content-type": "text/html" } });
});

test("authenticate: canonical parse, owner ordering, memoized sha identities", async () => {
  const env = baseEnv(makeKv());
  const owner = await authenticate(reqFor("/x", { token: OWNER }), env);
  assert.equal(owner.isOwner, true);
  assert.equal(owner.identity, idOf(OWNER));
  assert.match(owner.identity, /^[0-9a-f]{16}$/);

  const friend = await authenticate(reqFor("/x", { token: FRIEND }), env);
  assert.equal(friend.isOwner, false);
  assert.equal(friend.identity, idOf(FRIEND));

  // The duplicate FRIEND entry must not shift ownership or identity; FRIEND2 still resolves.
  const friend2 = await authenticate(reqFor("/x", { token: FRIEND2 }), env);
  assert.equal(friend2.isOwner, false);
  assert.equal(friend2.identity, idOf(FRIEND2));

  for (const bad of [
    { token: "wrong-token-000000" },
    {}, // no header at all
    { token: "   " }, // whitespace header can never match (empties are dropped from the list)
    { token: "" },
  ]) {
    const r = await authenticate(reqFor("/x", bad), env);
    assert.ok(r.denied, `expected denial for ${JSON.stringify(bad)}`);
    assert.equal(r.denied.status, 401);
  }
});

test("authenticate: changed-list revocation (new env invalidates the memo)", async () => {
  const kv = makeKv();
  assert.equal((await authenticate(reqFor("/x", { token: FRIEND }), baseEnv(kv))).identity, idOf(FRIEND));
  const revoked = baseEnv(kv, `${OWNER},${FRIEND2}`); // FRIEND removed + redeploy
  const r = await authenticate(reqFor("/x", { token: FRIEND }), revoked);
  assert.ok(r.denied);
  assert.equal(r.denied.status, 401);
  // The survivors keep working, and ownership stays with the first entry.
  assert.equal((await authenticate(reqFor("/x", { token: OWNER }), revoked)).isOwner, true);
  assert.equal((await authenticate(reqFor("/x", { token: FRIEND2 }), revoked)).isOwner, false);
});

test("open worker (unset/empty secret): null-identity auth, provider gates fail closed", async () => {
  for (const raw of [undefined, "", " , ,"]) {
    const env = { STATS_KV: makeKv() };
    if (raw !== undefined) env.STATS_TOKEN = raw;
    const auth = await authenticate(reqFor("/x", {}), env);
    assert.deepEqual(auth, { identity: null, isOwner: false });
    assert.equal(urchinAllowed(auth, reqFor("/x", { optUrchin: true })), false);
    assert.equal(seraphAllowed(auth, reqFor("/x", { optSeraph: true })), false);
  }
  // And the checkAuth-tier routes really are open, exactly as today.
  const w = makeCtx();
  const res = await worker.fetch(reqFor("/bedwars/MatrixGuy", {}), { STATS_KV: makeKv(), HYPIXEL_BASE: "https://hy.test" }, w.ctx);
  assert.equal(res.status, 200);
  assert.equal((await res.json()).cached, true);
});

test("route matrix: owner/friend/second-friend accepted, bad callers rejected on every route", async () => {
  const UUID = "069a79f444e94726a5befca90e38aaf5";
  const routes = [
    { path: "/health" },
    { path: "/test/MatrixGuy" },
    { path: "/bedwars/MatrixGuy" },
    { path: "/bedwars/batch?names=BatchGuy", stream: true },
    { path: `/urchin/${"MatrixGuy"}`, optUrchin: true, deniedStatus: 403 },
    { path: `/seraph/${UUID}`, optSeraph: true, deniedStatus: 403 },
    { path: "/urchin/key", method: "POST", body: () => JSON.stringify({ key: "matrix-key-0123456789" }), deniedStatus: 403 },
    { path: "/seraph/key", method: "POST", body: () => JSON.stringify({ key: "matrix-key-0123456789" }), deniedStatus: 403 },
  ];
  for (const route of routes) {
    for (const good of [OWNER, FRIEND, FRIEND2]) {
      const w = makeCtx();
      const res = await worker.fetch(
        reqFor(route.path, { token: good, optUrchin: route.optUrchin, optSeraph: route.optSeraph, method: route.method, body: route.body && route.body() }),
        baseEnv(makeKv()),
        w.ctx
      );
      assert.equal(res.status, 200, `${route.path} should accept a listed token`);
      if (route.stream) await res.text(); // drain the NDJSON stream
      await w.settle();
    }
    for (const bad of [{ token: "wrong-token-000000" }, {}, { token: "   " }]) {
      const w = makeCtx();
      const res = await worker.fetch(
        reqFor(route.path, { ...bad, optUrchin: route.optUrchin, optSeraph: route.optSeraph, method: route.method, body: route.body && route.body() }),
        baseEnv(makeKv()),
        w.ctx
      );
      assert.equal(res.status, route.deniedStatus || 401, `${route.path} must reject ${JSON.stringify(bad)}`);
    }
  }
});

test("token grammar: entries outside ^[A-Za-z0-9_-]{16,64}$ are dropped, never credentials", async () => {
  const SHORT = "shorty_123"; // < 16 chars
  const SPACED = "has an_internal_space_0123"; // internal whitespace
  const LONG = "L".repeat(65); // > 64 chars
  const env = baseEnv(makeKv(), `${SHORT},${SPACED},${LONG},${OWNER}`);
  for (const bad of [SHORT, SPACED, LONG]) {
    const r = await authenticate(reqFor("/x", { token: bad }), env);
    assert.ok(r.denied, `invalid list entry ${JSON.stringify(bad)} must never authenticate`);
    assert.equal(r.denied.status, 401);
  }
  // The surviving valid entry still works.
  assert.equal((await authenticate(reqFor("/x", { token: OWNER }), env)).identity, idOf(OWNER));
});

test("token grammar: an invalid FIRST entry does not become owner - first VALID entry is", async () => {
  const env = baseEnv(makeKv(), `bad token,${FRIEND},${OWNER}`);
  const first = await authenticate(reqFor("/x", { token: FRIEND }), env);
  assert.equal(first.isOwner, true, "the first VALID entry must be the owner");
  const second = await authenticate(reqFor("/x", { token: OWNER }), env);
  assert.equal(second.isOwner, false);
  assert.ok((await authenticate(reqFor("/x", { token: "bad token" }), env)).denied);
});

test("token grammar fail-closed: an all-invalid list denies everything, never falls open", async () => {
  const env = baseEnv(makeKv(), "x, y z ,!!!invalid!!!");
  for (const attempt of [{ token: "x" }, { token: "y z" }, {}]) {
    const r = await authenticate(reqFor("/x", attempt), env);
    assert.ok(r.denied, `must deny ${JSON.stringify(attempt)}`);
    assert.equal(r.denied.status, 401);
  }
  // Route level: even the plain stats route is closed (contrast with the open-when-unset test).
  const w = makeCtx();
  const res = await worker.fetch(reqFor("/bedwars/MatrixGuy", { token: "x" }), env, w.ctx);
  assert.equal(res.status, 401);
});

test("provider data routes still demand the opt-in header on top of a valid token", async () => {
  const w = makeCtx();
  const res = await worker.fetch(reqFor("/urchin/MatrixGuy", { token: FRIEND }), baseEnv(makeKv()), w.ctx);
  assert.equal(res.status, 403);
  const res2 = await worker.fetch(
    reqFor("/seraph/069a79f444e94726a5befca90e38aaf5", { token: FRIEND }), baseEnv(makeKv()), w.ctx);
  assert.equal(res2.status, 403);
});
