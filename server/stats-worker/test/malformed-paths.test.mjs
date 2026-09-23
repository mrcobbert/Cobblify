/**
 * W3: malformed percent-encoding must be a 400, never a throw out of `worker.fetch` (workerd turns
 * an uncaught throw into a 500). `decodeURIComponent` raises URIError on a bad escape, and every
 * name/uuid path segment fed it unguarded.
 *
 * The batch route needs a different guard: `URLSearchParams` never throws on a malformed escape, so
 * the RAW query is strict-checked first; the names it then returns are already decoded once, and
 * decoding them a second time both threw on `%25zz` and would corrupt a literal "%" name.
 *
 * In-process `worker.fetch` with stubbed `caches` and `fetch`, so nothing binds a socket.
 */
import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/worker.js";

const TOKEN = "Owner_tok_0123456789abcdef";
const ENV = { STATS_TOKEN: TOKEN, HYPIXEL_BASE: "https://hy.test" };

const STATS = ["Wins", "Losses", "Kills", "Deaths", "Final Kills", "Final Deaths"];
const OK_HTML = (() => {
  const rows = STATS.map((s) => `<tr><td>${s}</td><td>10</td></tr>`);
  for (const m of ["Solo", "Doubles", "3v3v3v3", "4v4v4v4"]) for (const s of STATS) rows.push(`<tr><td>${m} ${s}</td><td>2</td></tr>`);
  return `<html><body><div id="stats-content-bedwars"><table>${rows.join("\n")}</table></div></body></html>`;
})();

/** L1 cache key for a player, as cache.js builds it. */
const l1Url = (name) => `https://bedwarsqol.internal/cache/bedwars/v2/${name.toLowerCase()}`;
const cachedBody = (name) => JSON.stringify({ success: true, state: "OK", displayName: name, wins: 1 });

function stubCaches(seed = {}) {
  const store = new Map(Object.entries(seed));
  globalThis.caches = {
    default: {
      async match(req) {
        const b = store.get(typeof req === "string" ? req : req.url);
        return b === undefined ? undefined : new Response(b, { headers: { "content-type": "application/json" } });
      },
      async put(req, res) { store.set(typeof req === "string" ? req : req.url, await res.text()); },
    },
  };
  return store;
}

function makeCtx() {
  const pending = [];
  return {
    ctx: { waitUntil(p) { pending.push(Promise.resolve(p).catch(() => {})); } },
    async settle() { while (pending.length) await Promise.all(pending.splice(0)); },
  };
}

/** Both provider opt-in headers, so the provider routes reach their own name/uuid validation. */
function req(path) {
  return new Request(`https://w.test${path}`, {
    headers: { "X-BedwarsQol-Token": TOKEN, "X-BWQOL-Urchin": "1", "X-BWQOL-Seraph": "1" },
  });
}

/** Drive one request to completion (body included) without letting a throw escape the test. */
async function call(path) {
  const w = makeCtx();
  let res;
  try {
    res = await worker.fetch(req(path), ENV, w.ctx);
  } catch (e) {
    await w.settle();
    assert.fail(`${path} threw out of worker.fetch: ${e && e.stack ? e.stack : e}`);
  }
  assert.ok(res instanceof Response, `${path} must answer with a Response`);
  const text = await res.text();
  await w.settle();
  return { status: res.status, text, contentType: res.headers.get("content-type") || "" };
}

const MALFORMED_PATHS = ["/bedwars/%zz", "/urchin/%E0%A4%A", "/test/%zz", "/seraph/%zz"];
const MALFORMED_QUERIES = ["/bedwars/batch?names=%zz", "/bedwars/batch?names=%E0%A4%A"];

test.beforeEach(() => {
  stubCaches();
  globalThis.fetch = async () => new Response(OK_HTML, { headers: { "content-type": "text/html" } });
});

test("a malformed escape in a path segment is a 400 invalid_player_name", async () => {
  for (const path of ["/bedwars/%zz", "/urchin/%E0%A4%A", "/test/%zz"]) {
    const { status, text } = await call(path);
    assert.equal(status, 400, `${path} -> ${status}`);
    const body = JSON.parse(text);
    assert.equal(body.error, "invalid_player_name", path);
    // The segment could not be decoded, so the echo is the raw segment.
    assert.equal(body.player, path.split("/")[2], path);
  }
});

test("a malformed escape in the seraph uuid segment is a 400 invalid_uuid", async () => {
  const { status, text } = await call("/seraph/%zz");
  assert.equal(status, 400);
  assert.equal(JSON.parse(text).error, "invalid_uuid");
});

test("a malformed RAW batch query is a 400 malformed_query", async () => {
  for (const path of MALFORMED_QUERIES) {
    const { status, text } = await call(path);
    assert.equal(status, 400, `${path} -> ${status}`);
    const body = JSON.parse(text);
    assert.equal(body.error, "malformed_query", path);
    assert.equal(body.success, false, path);
  }
});

test("names=%25zz is a VALID encoding of the invalid name %zz: 200, no lines", async () => {
  // Decoded exactly once by searchParams.get -> "%zz", which the name filter drops. A second
  // decode threw URIError here; a 400 would also be wrong, the query itself is well-formed.
  const { status, text, contentType } = await call("/bedwars/batch?names=%25zz");
  assert.equal(status, 200);
  assert.ok(contentType.includes("ndjson"), contentType);
  assert.equal(text, "", `expected no NDJSON lines, got ${JSON.stringify(text)}`);
});

test("names=Abc%2CDef is decoded exactly once: two NDJSON lines", async () => {
  stubCaches({ [l1Url("abc")]: cachedBody("Abc"), [l1Url("def")]: cachedBody("Def") });
  const { status, text } = await call("/bedwars/batch?names=Abc%2CDef");
  assert.equal(status, 200);
  const lines = text.split("\n").filter(Boolean).map((l) => JSON.parse(l));
  assert.deepEqual(lines.map((l) => l.name), ["Abc", "Def"]);
  assert.equal(lines[0].state, "OK");
  assert.equal(lines[0].cached, true);
});

test("no malformed input throws out of worker.fetch", async () => {
  for (const path of [...MALFORMED_PATHS, ...MALFORMED_QUERIES, "/bedwars/batch?names=%25zz"]) {
    const w = makeCtx();
    let res;
    try {
      res = await worker.fetch(req(path), ENV, w.ctx);
    } catch (e) {
      assert.fail(`${path} threw out of worker.fetch: ${e && e.stack ? e.stack : e}`);
    }
    assert.ok(res instanceof Response, path);
    await res.text();
    await w.settle();
  }
});
