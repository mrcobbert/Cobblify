/**
 * Seraph is retired (its public API shut down in October 2026). Clients up to 0.18 still opt in with
 * `X-BWQOL-Seraph: 1` and may call /seraph/<uuid> and /seraph/key. These tests pin, through the
 * real worker.fetch handler:
 *
 *  - the header adds ZERO upstream requests on any route (host lists with and without it are equal,
 *    including the shmeado fallback while the origin is marked blocked), and nothing contacts a
 *    Seraph host;
 *  - every opted-in lookup gets the final answer an old client stops asking on: `seraphUuid` (the
 *    batch merge key) + `seraphUnavailable`, inline on the base line, never a follow-up line;
 *  - the manual and key routes keep their gates, answer without upstream calls, and a key clear
 *    reports the truth about the KV deletes.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../src/worker.js";

const OWNER = "OwnerToken_retired_seraph01";
const FRIEND = "FriendToken_retired_seraph1";
const idOf = (t) => createHash("sha256").update(t).digest("hex").slice(0, 16);
const SERAPH_FIELDS = ["seraph", "seraphChecked", "seraphNotFound", "seraphUnavailable", "seraphUuid", "seraphUpdate"];
const seraphKeys = (o) => Object.keys(o).filter((k) => /seraph/i.test(k)).sort();

const STATS = ["Wins", "Losses", "Kills", "Deaths", "Final Kills", "Final Deaths"];
const OK_HTML = (() => {
  const rows = STATS.map((s) => `<tr><td>${s}</td><td>10</td></tr>`);
  for (const m of ["Solo", "Doubles", "3v3v3v3", "4v4v4v4"]) for (const s of STATS) rows.push(`<tr><td>${m} ${s}</td><td>2</td></tr>`);
  return `<html><body><div id="stats-content-bedwars"><table>${rows.join("\n")}</table></div></body></html>`;
})();

function stubCaches() {
  globalThis.caches = { default: { async match() { return undefined; }, async put() {} } };
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

/** Every outgoing request's hostname, in order. Hypixel/shmeado serve the stats page, Coral an empty set. */
let hosts = [];
function installFetch() {
  hosts = [];
  globalThis.fetch = async (url) => {
    const u = new URL(String(url));
    hosts.push(u.hostname);
    if (u.hostname === "coral.test") {
      return new Response(JSON.stringify({ players: {} }), { headers: { "content-type": "application/json" } });
    }
    return new Response(OK_HTML, { headers: { "content-type": "text/html" } });
  };
}

function env(extra = {}) {
  return { STATS_TOKEN: `${OWNER},${FRIEND}`, HYPIXEL_BASE: "https://hy.test", URCHIN_BASE: "https://coral.test", ...extra };
}

/** One request to completion against a fresh cache; returns status, parsed lines/body and hosts hit. */
async function run(path, { token = OWNER, seraph = false, urchin = false, method = "GET", body, e = env() } = {}) {
  stubCaches();
  installFetch();
  const headers = {};
  if (token) headers["X-BedwarsQol-Token"] = token;
  if (seraph) headers["X-BWQOL-Seraph"] = "1";
  if (urchin) headers["X-BWQOL-Urchin"] = "1";
  const w = makeCtx();
  const res = await worker.fetch(new Request(`https://w.test${path}`, { method, headers, body }), e, w.ctx);
  const text = await res.text();
  await w.settle();
  const isNdjson = (res.headers.get("content-type") || "").includes("ndjson");
  const lines = isNdjson ? text.split("\n").filter(Boolean).map((l) => JSON.parse(l)) : null;
  return { status: res.status, text, lines, json: isNdjson ? null : JSON.parse(text), hosts: [...hosts].sort() };
}

const U1 = "069a79f444e94726a5befca90e38aaf5";
const UA = "11111111111111111111111111111111";
const UB = "22222222222222222222222222222222";

test("single: the opted-in answer is final (uuid + unavailable) and costs no upstream request", async () => {
  const withHeader = await run(`/bedwars/RetiredOne?uuid=${U1}`, { seraph: true });
  const without = await run(`/bedwars/RetiredOne?uuid=${U1}`);
  assert.equal(withHeader.status, 200);
  assert.equal(withHeader.json.seraphUuid, U1);
  assert.equal(withHeader.json.seraphUnavailable, true);
  assert.deepEqual(seraphKeys(withHeader.json), ["seraphUnavailable", "seraphUuid"]);
  assert.deepEqual(seraphKeys(without.json), []);
  assert.deepEqual(withHeader.hosts, without.hosts, "the header must add no request");
  assert.ok(withHeader.hosts.length > 0, "a cold player is scraped (the comparison is not vacuous)");
  for (const h of withHeader.hosts) assert.ok(!/seraph/i.test(h), `contacted ${h}`);
});

test("single: dashed uuid is canonicalised; missing or bad uuid adds nothing", async () => {
  const dashed = await run("/bedwars/RetiredTwo?uuid=069a79f4-44e9-4726-a5be-fca90e38aaf5", { seraph: true });
  assert.equal(dashed.json.seraphUuid, U1);
  assert.deepEqual(seraphKeys((await run("/bedwars/RetiredTwo", { seraph: true })).json), []);
  assert.deepEqual(seraphKeys((await run("/bedwars/RetiredTwo?uuid=nope", { seraph: true })).json), []);
});

test("single, origin marked blocked (shmeado fallback): same hosts with and without the header", async () => {
  const blocked = () => env({ STATS_KV: makeKv({ "bw:blocked:v1": "1" }) });
  const withHeader = await run(`/bedwars/RetiredThree?uuid=${U1}`, { seraph: true, e: blocked() });
  const without = await run(`/bedwars/RetiredThree?uuid=${U1}`, { e: blocked() });
  assert.deepEqual(withHeader.hosts, without.hosts);
  assert.ok(withHeader.hosts.includes("shmeado.club"), "the fallback source really ran");
  assert.equal(withHeader.json.seraphUnavailable, true);
});

test("single with Urchin and Seraph opt-in: Coral traffic is exactly the Urchin-only traffic", async () => {
  const kv = () => makeKv({ [`urchin:cfg:key:${idOf(OWNER)}`]: "OWNER-URCHIN-KEY-01" });
  const both = await run(`/bedwars/RetiredFour?uuid=${U1}`, { seraph: true, urchin: true, e: env({ STATS_KV: kv() }) });
  const urchinOnly = await run(`/bedwars/RetiredFour?uuid=${U1}`, { urchin: true, e: env({ STATS_KV: kv() }) });
  assert.deepEqual(both.hosts, urchinOnly.hosts);
  assert.ok(both.hosts.includes("coral.test"), "the Urchin lookup really ran");
  assert.equal(both.json.seraphUnavailable, true);
  assert.equal(both.json.seraphUuid, U1);
});

test("batch: every aligned base line carries the final answer inline; no follow-up lines; no extra requests", async () => {
  const path = `/bedwars/batch?names=AlphaRet,BetaRet,PlainRet&uuids=${UA},${UB},-`;
  const withHeader = await run(path, { seraph: true });
  const without = await run(path);
  assert.equal(withHeader.status, 200);
  assert.deepEqual(withHeader.hosts, without.hosts);
  for (const h of withHeader.hosts) assert.ok(!/seraph/i.test(h), `contacted ${h}`);
  const byName = Object.fromEntries(withHeader.lines.map((l) => [l.name, l]));
  assert.equal(withHeader.lines.length, 3, "one base line per player, no follow-ups");
  for (const l of withHeader.lines) {
    assert.ok(typeof l.state === "string", "every line is a base line");
    assert.equal(l.seraphUpdate, undefined);
  }
  assert.deepEqual([byName.AlphaRet.seraphUuid, byName.AlphaRet.seraphUnavailable], [UA, true]);
  assert.deepEqual([byName.BetaRet.seraphUuid, byName.BetaRet.seraphUnavailable], [UB, true]);
  assert.deepEqual(seraphKeys(byName.PlainRet), [], "a '-' member gets nothing");
  for (const l of without.lines) assert.deepEqual(seraphKeys(l), []);
});

test("batch: misaligned uuids (count mismatch) -> no Seraph fields, like any provider", async () => {
  const r = await run(`/bedwars/batch?names=AlphaRet,BetaRet&uuids=${UA}`, { seraph: true });
  for (const l of r.lines) assert.deepEqual(seraphKeys(l), []);
});

test("unauthenticated or open worker: the opt-in header is ignored (fail closed, as before)", async () => {
  const open = await run(`/bedwars/RetiredFive?uuid=${U1}`, { token: null, seraph: true, e: { HYPIXEL_BASE: "https://hy.test" } });
  assert.equal(open.status, 200);
  assert.deepEqual(seraphKeys(open.json), []);
  const wrong = await run(`/bedwars/RetiredFive?uuid=${U1}`, { token: "wrong-token-000000", seraph: true });
  assert.equal(wrong.status, 401);
});

test("GET /seraph/<uuid>: final unavailable answer, no request; gate and validation unchanged", async () => {
  const ok = await run(`/seraph/${U1}`, { seraph: true });
  assert.equal(ok.status, 200);
  assert.deepEqual(ok.json, { success: true, uuid: U1, tags: [], notFound: false, unavailable: true });
  assert.deepEqual(ok.hosts, []);
  assert.equal((await run(`/seraph/${U1}`)).status, 403, "no opt-in header");
  assert.equal((await run(`/seraph/${U1}`, { token: FRIEND, seraph: true })).status, 200, "any listed identity");
  assert.equal((await run(`/seraph/${U1}`, { token: null, seraph: true, e: {} })).status, 403, "open worker");
  const bad = await run("/seraph/not-a-uuid", { seraph: true });
  assert.equal(bad.status, 400);
  assert.equal(bad.json.error, "invalid_uuid");
});

test("POST /seraph/key clear: deletes only the caller's entries, awaited, then success", async () => {
  const me = idOf(FRIEND);
  const other = idOf(OWNER);
  const kv = makeKv({
    [`seraph:cfg:key:${me}`]: "OLD-SERAPH-KEY-0001",
    [`seraph:cfg:backoff:${me}`]: "1",
    [`seraph:cfg:disabled:${me}`]: "1",
    [`seraph:cfg:key:${other}`]: "OWNER-SERAPH-KEY-01",
  });
  const r = await run("/seraph/key", { token: FRIEND, method: "POST", body: JSON.stringify({ key: null }), e: env({ STATS_KV: kv }) });
  assert.equal(r.status, 200);
  assert.deepEqual(r.json, { success: true });
  assert.deepEqual([...kv.store.keys()], [`seraph:cfg:key:${other}`], "only the caller's three entries go");
  assert.deepEqual(r.hosts, []);
});

test("POST /seraph/key clear: a failing KV delete is reported, never a false success", async () => {
  const kv = makeKv({ [`seraph:cfg:key:${idOf(OWNER)}`]: "OLD-SERAPH-KEY-0001" });
  kv.delete = async () => { throw new Error("KV down"); };
  const r = await run("/seraph/key", { method: "POST", body: JSON.stringify({ key: null }), e: env({ STATS_KV: kv }) });
  assert.equal(r.status, 503);
  assert.deepEqual(r.json, { success: false, error: "kv_write_failed" });
  assert.equal(kv.store.has(`seraph:cfg:key:${idOf(OWNER)}`), true);
});

test("POST /seraph/key clear without KV: nothing can be stored, so the clear is true", async () => {
  const r = await run("/seraph/key", { method: "POST", body: JSON.stringify({ key: null }) });
  assert.equal(r.status, 200);
  assert.deepEqual(r.json, { success: true });
});

test("POST /seraph/key set: 410 seraph_retired, nothing stored, key never echoed", async () => {
  const kv = makeKv();
  const r = await run("/seraph/key", { method: "POST", body: JSON.stringify({ key: "NEW-SERAPH-KEY-0001" }), e: env({ STATS_KV: kv }) });
  assert.equal(r.status, 410);
  assert.deepEqual(r.json, { success: false, error: "seraph_retired" });
  assert.equal(kv.store.size, 0);
  assert.ok(!r.text.includes("NEW-SERAPH-KEY-0001"));
  assert.deepEqual(r.hosts, []);
});

test("POST /seraph/key: bad body 400, no identity 403", async () => {
  assert.equal((await run("/seraph/key", { method: "POST", body: "not json" })).status, 400);
  assert.equal((await run("/seraph/key", { method: "POST", body: JSON.stringify({}) })).status, 400);
  assert.equal((await run("/seraph/key", { token: "wrong-token-000000", method: "POST", body: JSON.stringify({ key: null }) })).status, 403);
  assert.equal((await run("/seraph/key", { token: null, method: "POST", body: JSON.stringify({ key: null }), e: {} })).status, 403);
});

test("no Seraph field name is ever emitted except the two final-answer fields", async () => {
  const single = await run(`/bedwars/RetiredSix?uuid=${U1}`, { seraph: true });
  for (const k of seraphKeys(single.json)) assert.ok(["seraphUuid", "seraphUnavailable"].includes(k), k);
  for (const f of SERAPH_FIELDS) if (!["seraphUuid", "seraphUnavailable"].includes(f)) assert.equal(single.json[f], undefined, f);
});
