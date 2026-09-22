/**
 * W1: an in-flight cold scrape is shared per player (scrape.js IN_FLIGHT), and the routes add
 * per-identity provider fields (urchin and seraph keys) to the body they receive. Every caller must
 * therefore get its OWN body: one identity's opt-in fields, or its "resolved unavailable" flag,
 * must never ride into another identity's response - single or batch, Urchin or Seraph.
 *
 * In-process worker.fetch with stubbed caches (never hit), KV, and fetch (300 ms origin delay so
 * the second request joins the first's in-flight scrape). Every case asserts exactly one origin
 * fetch, which is what proves the second request piggybacked rather than scraping on its own.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker from "../src/worker.js";
import { getBedwars } from "../src/scrape.js";

const idOf = (t) => createHash("sha256").update(t).digest("hex").slice(0, 16);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
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

let n = 0;
/** Fresh tokens (identities), name and uuid per case so module-level state never leaks. */
function fresh() {
  n++;
  return {
    owner: `Owner_${n}_0123456789abcdef`,
    friend: `Friend_${n}_0123456789abcde`,
    name: `ColdGuy${n}`,
    uuid: (0x700 + n).toString(16).padStart(32, "0"),
  };
}

const providerKeys = (obj) => Object.keys(obj).filter((k) => /urchin|seraph/i.test(k)).sort();

/** Origin (hypixel) delayed 300 ms; Coral and Seraph answer instantly with an "ok" result. */
function installFetch(s) {
  const counts = { origin: 0 };
  globalThis.fetch = async (url) => {
    const u = String(url);
    if (u.startsWith("https://coral.test")) {
      return new Response(JSON.stringify({ players: { [s.uuid]: [{ tag_type: "blatant_cheater", reason: "kb", added_on: 1 }] } }),
        { headers: { "content-type": "application/json" } });
    }
    if (u.startsWith("https://seraph.test")) {
      return new Response(JSON.stringify({ data: {} }), { headers: { "content-type": "application/json" } });
    }
    counts.origin++;
    await sleep(300);
    return new Response(OK_HTML, { headers: { "content-type": "text/html" } });
  };
  return counts;
}

const HEADER = { urchin: "X-BWQOL-Urchin", seraph: "X-BWQOL-Seraph" };
function baseEnv(s, extra = {}) {
  return { STATS_TOKEN: `${s.owner},${s.friend}`, HYPIXEL_BASE: "https://hy.test", URCHIN_BASE: "https://coral.test", SERAPH_BASE: "https://seraph.test", ...extra };
}
function req(s, path, token, provider) {
  return new Request(`https://w.test${path}`, { headers: { "X-BedwarsQol-Token": token, ...(provider ? { [HEADER[provider]]: "1" } : {}) } });
}
/** Parse a response: single -> the JSON object; batch -> the merged fields of lines for `name`. */
async function fieldsOf(res, name) {
  const text = await res.text();
  if ((res.headers.get("content-type") || "").includes("ndjson")) {
    const fields = {};
    for (const line of text.split("\n").filter(Boolean).map((l) => JSON.parse(l))) {
      if (line.name === name) Object.assign(fields, line);
    }
    return fields;
  }
  return JSON.parse(text);
}

/**
 * Drive two requests 30 ms apart against one cold player and return both bodies plus the origin
 * count. `first` starts the scrape; `second` joins it.
 */
async function race(env, first, second, name) {
  const w1 = makeCtx();
  const w2 = makeCtx();
  const p1 = worker.fetch(first, env, w1.ctx).then((r) => fieldsOf(r, name));
  await sleep(30);
  const p2 = worker.fetch(second, env, w2.ctx).then((r) => fieldsOf(r, name));
  const [a, b] = await Promise.all([p1, p2]);
  await w1.settle();
  await w2.settle();
  return { a, b };
}

test.beforeEach(() => { stubCaches(); });

for (const provider of ["urchin", "seraph"]) {
  const Flag = provider === "urchin" ? "urchinUnavailable" : "seraphUnavailable";

  test(`${provider}: owner single without KV -> only the owner sees "${Flag}"`, async () => {
    const s = fresh();
    const counts = installFetch(s);
    const env = baseEnv(s); // no STATS_KV: provider incapable -> resolved unavailable, synchronously
    const { a: owner, b: friend } = await race(env,
      req(s, `/bedwars/${s.name}?uuid=${s.uuid}`, s.owner, provider),
      req(s, `/bedwars/${s.name}`, s.friend, null), s.name);
    assert.equal(counts.origin, 1, "the friend must have joined the owner's in-flight scrape");
    assert.equal(owner[Flag], true);
    assert.equal(owner.state, "OK");
    assert.deepEqual(providerKeys(friend), [], `friend leaked ${JSON.stringify(providerKeys(friend))}`);
    assert.equal(friend.state, "OK");
  });

  test(`${provider}: owner batch with a key -> the friend's single carries no provider field`, async () => {
    const s = fresh();
    const counts = installFetch(s);
    const kv = makeKv({ [`${provider}:cfg:key:${idOf(s.owner)}`]: "OWNER-KEY-0123456789" });
    const env = baseEnv(s, { STATS_KV: kv });
    const { a: owner, b: friend } = await race(env,
      req(s, `/bedwars/batch?names=${s.name}&uuids=${s.uuid}`, s.owner, provider),
      req(s, `/bedwars/${s.name}`, s.friend, null), s.name);
    assert.equal(counts.origin, 1);
    assert.ok(providerKeys(owner).length > 0, "the owner's own line must carry its provider fields");
    assert.equal(owner[`${provider}Checked`], true);
    assert.deepEqual(providerKeys(friend), [], `friend leaked ${JSON.stringify(providerKeys(friend))}`);
  });

  test(`${provider}: the mirror - the friend opts in, the owner (initiator) does not`, async () => {
    const s = fresh();
    const counts = installFetch(s);
    const kv = makeKv({ [`${provider}:cfg:key:${idOf(s.friend)}`]: "FRIEND-KEY-0123456789" });
    const env = baseEnv(s, { STATS_KV: kv });
    const { a: owner, b: friend } = await race(env,
      req(s, `/bedwars/${s.name}`, s.owner, null),
      req(s, `/bedwars/${s.name}?uuid=${s.uuid}`, s.friend, provider), s.name);
    assert.equal(counts.origin, 1);
    assert.equal(friend[`${provider}Checked`], true);
    assert.deepEqual(providerKeys(owner), [], `owner leaked ${JSON.stringify(providerKeys(owner))}`);
  });

  test(`${provider}: a non-opted BATCH recipient joining an opted-in batch gets a clean line`, async () => {
    const s = fresh();
    const counts = installFetch(s);
    const kv = makeKv({ [`${provider}:cfg:key:${idOf(s.owner)}`]: "OWNER-KEY-0123456789" });
    const env = baseEnv(s, { STATS_KV: kv });
    const { a: owner, b: friend } = await race(env,
      req(s, `/bedwars/batch?names=${s.name}&uuids=${s.uuid}`, s.owner, provider),
      req(s, `/bedwars/batch?names=${s.name}`, s.friend, null), s.name);
    assert.equal(counts.origin, 1);
    assert.equal(owner[`${provider}Checked`], true);
    assert.deepEqual(providerKeys(friend), [], `friend leaked ${JSON.stringify(providerKeys(friend))}`);
    assert.equal(friend.state, "OK");
  });
}

test("two plain callers of one in-flight scrape receive distinct body objects with equal content", async () => {
  const s = fresh();
  const counts = installFetch(s);
  const env = baseEnv(s);
  const w = makeCtx();
  const p1 = getBedwars(s.name, env, w.ctx, false);
  await sleep(30);
  const p2 = getBedwars(s.name, env, w.ctx, false);
  const [a, b] = await Promise.all([p1, p2]);
  await w.settle();
  assert.equal(counts.origin, 1);
  assert.notEqual(a, b, "each caller must own its body");
  assert.deepEqual(a, b);
  a.marker = true;
  assert.equal("marker" in b, false, "a mutation through one caller's body must not show in the other's");
});
