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

const TOKEN = "test-token_0123456789";
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
// A nicked player: hypixel has no such member, so /player/<nick> 404s. The real page is ~42 KB,
// carries Cloudflare's beacon script (that combination is what used to slip under the <50 KB
// challenge gate and be misread as a block), and is rendered by XenForo's error template — the
// marker that proves the forum itself answered, which is what licenses the NICKED verdict.
const NOT_FOUND_HTML =
  '<html data-template="error"><head><title>Oops! We ran into some problems. | Hypixel Forums' +
  "</title></head><body>" +
  '<script src="/cdn-cgi/challenge-platform/h/g/scripts/jsd/main.js"></script>' +
  "<!--" + "p".repeat(42_000) + "--></body></html>";
// A 404 that did NOT come from the forum app (moved route, edge error). Must stay a retryable
// ERROR: labelling this "nicked" would mislabel every player at once.
const EDGE_404_HTML = "<html><head><title>404 Not Found</title></head><body>edge</body></html>";
// Section needle present but none of the six labels resolve -> parse_failed.
const PARSE_FAIL_HTML = '<html><body><div id="stats-content-bedwars">markup changed</div></body></html>';
// Challenge page (under 50 KB + challenge words) -> blocked_by_cloudflare.
const CHALLENGE_HTML = "<html><head><title>Just a moment...</title></head><body></body></html>";
// Minimal shmeado page shape (SHMEADO_BASE also points at this fixture): the blocked state now
// forks to the shmeado fallback instead of short-circuiting, and tests must never touch the
// real site.
const SHMEADO_HTML =
  "<html><body><script>window.player={name:`PlayerB`,rank:{rank:`None`},stats:{bedwars:" +
  "{wins_bedwars:3,losses_bedwars:1,kills_bedwars:5,deaths_bedwars:2,final_kills_bedwars:4," +
  "final_deaths_bedwars:1}},achievements:{bedwars_level:12}}</script></body></html>";

const hits = new Map(); // player -> exact /player/<name> hit count (achievements hits ignored)
const hitCount = (name) => hits.get(name) || 0;
const shmeadoHits = new Map(); // player -> /player/stats/<name>/ hit count (the fallback origin)
const shmeadoHitCount = (name) => shmeadoHits.get(name) || 0;

const fixture = http.createServer((req, res) => {
  res.setHeader("content-type", "text/html; charset=utf-8");
  const sm = new URL(req.url, "http://x").pathname.match(/^\/player\/stats\/([^/]+)\/$/);
  if (sm) {
    const name = decodeURIComponent(sm[1]);
    shmeadoHits.set(name, shmeadoHitCount(name) + 1);
    res.end(SHMEADO_HTML);
    return;
  }
  const m = new URL(req.url, "http://x").pathname.match(/^\/player\/([^/]+)$/);
  if (!m) { res.end("<html><body>no achievements here</body></html>"); return; }
  const name = decodeURIComponent(m[1]);
  hits.set(name, hitCount(name) + 1);
  if (name === "PlayerA") { res.end(CHALLENGE_HTML); return; }
  if (name === "ParseFailGuy") { res.end(PARSE_FAIL_HTML); return; }
  if (name === "OkGuy" || name === "OkGuy2") { res.end(OK_HTML); return; }
  if (name === "Nick404Guy") { res.statusCode = 404; res.end(NOT_FOUND_HTML); return; }
  if (name === "Edge404Guy") { res.statusCode = 404; res.end(EDGE_404_HTML); return; }
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
      SHMEADO_BASE: `http://${lan}:${fixture.address().port}`,
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

// A 404 is hypixel answering "no such member" — the normal case for a /nick'd player. It must
// read as NICKED (so the mod renders "[Nicked]" instead of a blank cell), cache on the stable
// NICKED TTL, and above all NOT trip the global blocked flag: one nicked player in one lobby
// used to knock every user of the shared worker off the hypixel origin for 120 s.
test("a 404 player page is NICKED, caches, and does not trip the blocked flag", async () => {
  const b1 = await (await req("/bedwars/Nick404Guy")).json();
  assert.equal(b1.success, false);
  assert.equal(b1.state, "NICKED");
  assert.equal(b1.displayName, "Nick404Guy");
  assert.equal(b1.error, undefined, "a nick is an answer, not a failure");
  assert.equal(hitCount("Nick404Guy"), 1);

  await sleep(200); // let the waitUntil cache writes commit
  const b2 = await (await req("/bedwars/Nick404Guy")).json();
  assert.equal(b2.state, "NICKED");
  assert.equal(b2.cached, true);
  assert.equal(hitCount("Nick404Guy"), 1, "NICKED caches on the stable TTL, not per-lookup");

  // The breaker is untouched: a fresh player still scrapes hypixel rather than the fallback.
  const b3 = await (await req("/bedwars/OkGuy2")).json();
  assert.equal(b3.state, "OK");
  assert.equal(hitCount("OkGuy2"), 1);
  assert.equal(shmeadoHitCount("OkGuy2"), 0);
});

// The guard on the verdict above: content cannot distinguish "no such member" from "the /player/
// route moved" once the forum answers, so the forum's own error template is what licenses NICKED.
// A 404 from anywhere else stays ERROR rather than confidently mislabelling a real player.
test("a 404 that the forum did not render stays ERROR, never NICKED", async () => {
  const b = await (await req("/bedwars/Edge404Guy")).json();
  assert.equal(b.success, false);
  assert.equal(b.state, "ERROR");
  assert.equal(b.error, "http_404");
});

// LAST: trips the global blocked flag for this worker instance.
test("challenge page trips the blocked flag: next player falls back to shmeado with zero hypixel traffic", async () => {
  const bA = await (await req("/bedwars/PlayerA")).json();
  assert.equal(bA.success, false);
  assert.equal(bA.state, "ERROR");
  assert.equal(bA.error, "blocked_by_cloudflare");
  assert.equal(hitCount("PlayerA"), 1);
  await sleep(200); // let the waitUntil blocked-flag writes commit
  const bB = await (await req("/bedwars/PlayerB")).json();
  assert.equal(bB.success, true);
  assert.equal(bB.state, "OK");
  assert.equal(bB.wins, 3);
  assert.equal(bB.bedwarsLevel, 12); // the star rides only fallback-served bodies
  assert.equal(hitCount("PlayerB"), 0); // hypixel is never touched while blocked
  assert.equal(shmeadoHitCount("PlayerB"), 1);
});
