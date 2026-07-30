/**
 * Negative-caching + blocked-circuit-breaker tests against the real Worker under
 * miniflare-in-wrangler (unstable_dev), with a local fixture standing in for hypixel.net
 * (HYPIXEL_BASE override). Asserts that NICKED/parse_failed outcomes cache (one origin hit
 * across repeat lookups), that a challenge page trips the global blocked flag (zero origin
 * traffic for subsequent players), and that the success path still caches after the
 * writeCached signature change.
 *
 * ORDER MATTERS: the blocked flag persists in caches.default for its TTL within this worker
 * instance, so the blocked test runs LAST — every earlier test must scrape normally.
 */
import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import os from "node:os";
import { once } from "node:events";

// workerd (wrangler local dev) cannot connect back to loopback on this platform, so the
// hypixel fixture binds 0.0.0.0 and the Worker reaches it via the machine's LAN address.
function lanAddress() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const i of ifaces || []) {
      if (i.family === "IPv4" && !i.internal) return i.address;
    }
  }
  return null;
}

const TOKEN = "test-token";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- Hypixel fixture -------------------------------------------------------
// Serves the minimal HTML each parser state needs; counts hits per player so the tests can
// assert exactly how many scrapes reached the "origin".

// Real page shape reduced to what bedwars-parse.js needs: the section needle plus flat
// "<label>\n<value>" pairs (tags become token separators).
const OK_HTML = [
  '<html><body><div id="stats-content-bedwars"><table>',
  "<tr><td>Wins</td><td>10</td></tr>",
  "<tr><td>Losses</td><td>5</td></tr>",
  "<tr><td>Kills</td><td>100</td></tr>",
  "<tr><td>Deaths</td><td>50</td></tr>",
  "<tr><td>Final Kills</td><td>20</td></tr>",
  "<tr><td>Final Deaths</td><td>4</td></tr>",
  "</table></div></body></html>",
].join("\n");
// Page loads but has no Bedwars section at all -> NICKED.
const NICKED_HTML = "<html><body><h1>Some profile without stats</h1></body></html>";
// Section needle present but none of the six labels resolve -> parse_failed.
const PARSE_FAIL_HTML = '<html><body><div id="stats-content-bedwars">markup changed</div></body></html>';
// Challenge page (under 50 KB + challenge words) -> blocked_by_cloudflare.
const CHALLENGE_HTML = "<html><head><title>Just a moment...</title></head><body></body></html>";

const hits = new Map(); // player -> exact /player/<name> hit count (achievements hits ignored)
const hitCount = (name) => hits.get(name) || 0;

const fixture = http.createServer((req, res) => {
  const m = new URL(req.url, "http://x").pathname.match(/^\/player\/([^/]+)$/);
  res.setHeader("content-type", "text/html; charset=utf-8");
  if (!m) { res.end("<html><body>no achievements here</body></html>"); return; }
  const name = decodeURIComponent(m[1]);
  hits.set(name, hitCount(name) + 1);
  if (name === "PlayerA") { res.end(CHALLENGE_HTML); return; }
  if (name === "ParseFailGuy") { res.end(PARSE_FAIL_HTML); return; }
  if (name === "OkGuy") { res.end(OK_HTML); return; }
  res.end(NICKED_HTML);
});

let worker;
let base;

test.before(async () => {
  fixture.listen(0, "0.0.0.0");
  await once(fixture, "listening");
  const lan = lanAddress();
  if (!lan) throw new Error("No LAN IPv4 address available for the hypixel fixture");
  const { unstable_dev } = await import("wrangler");
  worker = await unstable_dev("src/worker.js", {
    experimental: { disableExperimentalWarning: true },
    vars: {
      STATS_TOKEN: TOKEN,
      HYPIXEL_BASE: `http://${lan}:${fixture.address().port}`,
    },
    kv: [{ binding: "STATS_KV" }],
    local: true,
    // No filesystem state: hermetic per run, and no clash with routes.test.mjs (which wipes
    // and reuses .wrangler/state in a concurrently running process).
    persist: false,
  });
  base = `http://${worker.address}:${worker.port}`;
});

test.after(async () => {
  if (worker) await worker.stop();
  fixture.close();
});

function req(path) {
  return fetch(base + path, { headers: { "X-BedwarsQol-Token": TOKEN } });
}

test("NICKED is cached: second lookup serves from cache, one origin hit", async () => {
  const b1 = await (await req("/bedwars/NickGuy")).json();
  assert.equal(b1.state, "NICKED");
  await sleep(200); // let the waitUntil cache writes commit
  const b2 = await (await req("/bedwars/NickGuy")).json();
  assert.equal(b2.state, "NICKED");
  assert.equal(b2.success, false);
  assert.equal(b2.displayName, "NickGuy");
  assert.equal(b2.cached, true);
  // Served from L1 here; NICKED is also KV-durable (covered by kv-cache.test.mjs).
  assert.equal(hitCount("NickGuy"), 1);
});

test("parse_failed is cached briefly: one origin hit across two lookups", async () => {
  const b1 = await (await req("/bedwars/ParseFailGuy")).json();
  assert.equal(b1.error, "parse_failed");
  await sleep(200);
  const b2 = await (await req("/bedwars/ParseFailGuy")).json();
  assert.equal(b2.error, "parse_failed");
  assert.equal(b2.state, "ERROR");
  assert.equal(b2.cached, true);
  // Transient negatives are L1-only by policy: they must never reach KV.
  assert.equal(hitCount("ParseFailGuy"), 1);
});

test("success path still caches after the writeCached TTL change", async () => {
  const b1 = await (await req("/bedwars/OkGuy")).json();
  assert.equal(b1.state, "OK");
  assert.equal(b1.wins, 10);
  await sleep(200);
  const b2 = await (await req("/bedwars/OkGuy")).json();
  assert.equal(b2.state, "OK");
  assert.equal(b2.cached, true);
  assert.equal(b2.finalKills, 20);
  // Served from L1 here; the KV tier is exercised in kv-cache.test.mjs.
  assert.equal(hitCount("OkGuy"), 1);
});

test("batch serves a cached NICKED line with zero origin traffic", async () => {
  const before = hitCount("NickGuy");
  const text = await (await req("/bedwars/batch?names=NickGuy")).text();
  const line = JSON.parse(text.trim().split("\n")[0]);
  assert.equal(line.name, "NickGuy");
  assert.equal(line.success, false);
  assert.equal(line.state, "NICKED");
  assert.equal(line.displayName, "NickGuy");
  assert.equal(line.cached, true);
  assert.equal(hitCount("NickGuy"), before);
});

// LAST: trips the global blocked flag for this worker instance.
test("challenge page trips the blocked flag: next player short-circuits with zero origin traffic", async () => {
  const bA = await (await req("/bedwars/PlayerA")).json();
  assert.equal(bA.success, false);
  assert.equal(bA.state, "ERROR");
  assert.equal(bA.error, "blocked_by_cloudflare");
  assert.equal(hitCount("PlayerA"), 1);
  await sleep(200); // let the waitUntil blocked-flag writes commit
  const bB = await (await req("/bedwars/PlayerB")).json();
  assert.equal(bB.success, false);
  assert.equal(bB.state, "ERROR");
  assert.equal(bB.error, "blocked_by_cloudflare");
  assert.equal(hitCount("PlayerB"), 0); // short-circuited before any fetch
});
