/**
 * Route-level tests against the real Worker under miniflare-in-wrangler (unstable_dev),
 * with a local fixture standing in for Coral (URCHIN_BASE override). Asserts the gating
 * matrix (token x opt-in x KV x key), wire shapes, NDJSON follow-up ordering, and -
 * critically - the EXACT uuid array that reaches the Coral fixture (an unintended UUID is
 * an external privacy-affecting action).
 */
import test from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import os from "node:os";
import { once } from "node:events";

// workerd (wrangler local dev) cannot connect back to loopback on this platform, so the
// Coral fixture binds 0.0.0.0 and the Worker reaches it via the machine's LAN address.
function lanAddress() {
  for (const ifaces of Object.values(os.networkInterfaces())) {
    for (const i of ifaces || []) {
      if (i.family === "IPv4" && !i.internal) return i.address;
    }
  }
  return null;
}

const TOKEN = "test-token";
const KEY = "unit-test-key-abcdef";
const TAGGED = "069a79f444e94726a5befca90e38aaf5";
const UNTAGGED = "11111111222233334444555566667777";
// The fixture OMITS this uuid from the batch response (v3 skips entries it can't serve).
const OMITTED = "99999999888877776666555544443333";
// Used only by the key-placement test, which drives both upstream paths itself.
const KEYCHECK = "22222222333344445555666677778888";
// A realistic v3 `added_on`: unix MILLISECONDS int64 (live-observed magnitude). Pinned end
// to end because a toMs regression on integers would zero every addedOn silently, and
// addedOn drives the age text in the in-game alert hover.
const ADDED_ON_MS = 1783732549488;

// The real v3 TagResponse reporter fields. None of them may survive into our output:
// added_by_username in particular carries a human identity.
const REPORTER = { added_by: 421337, added_by_username: "SomeReporter", hide_username: false };
const REPORTER_FIELDS = ["added_by", "added_by_username", "hide_username"];

const BATCH_PATH = "/v3/players";
const NAME_PATH = "/v3/player/tags";

// ---- Coral fixture (v3) ----------------------------------------------------
const coralCalls = [];
const fixture = http.createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const parsed = new URL(req.url, "http://x");
    coralCalls.push({
      method: req.method,
      url: parsed.pathname,
      rawUrl: req.url,
      body,
      apiKeyHeader: req.headers["x-api-key"] || null,
      queryKey: parsed.searchParams.get("key"),
    });
    // v3 auth: the key MUST arrive as a header. Anything else is a real 401 with an EMPTY
    // body (not JSON) - the shape that would break a parse-before-status-check client.
    if (req.headers["x-api-key"] !== KEY) {
      res.statusCode = 401;
      res.end();
      return;
    }
    res.setHeader("content-type", "application/json");
    // v3 batch: uuids inside `uuids`, echoed back undashed-lowercase as the response keys.
    // Checked-and-clean is PRESENT with []; a skipped entry is OMITTED entirely.
    if (req.method === "POST" && parsed.pathname === BATCH_PATH) {
      const uuids = JSON.parse(body).uuids || [];
      const players = {};
      for (const u of uuids) {
        if (u === OMITTED) continue;
        players[u] = u === TAGGED
          ? [{ tag_type: "blatant_cheater", reason: "kb", added_on: ADDED_ON_MS, ...REPORTER }]
          : [];
      }
      res.end(JSON.stringify({ players }));
      return;
    }
    if (req.method === "GET" && parsed.pathname === NAME_PATH) {
      const name = parsed.searchParams.get("player") || "";
      if (name === "NoSuchPlayer") {
        res.statusCode = 404;
        res.end(JSON.stringify({ error: "Player not found" }));
        return;
      }
      res.end(JSON.stringify({
        uuid: TAGGED,
        displayname: name,
        tags: name === "TaggedGuy" ? [{ tag_type: "sniper", reason: "q", added_on: ADDED_ON_MS, ...REPORTER }] : [],
      }));
      return;
    }
    res.statusCode = 500;
    res.end("{}");
  });
});

// ---- Hypixel fixture -------------------------------------------------------
// Plain non-challenge HTML for every path: scrape misses resolve as NICKED and the /health
// probe gets a real (UNEXPECTED_HTML) round-trip, all without touching hypixel.net.
const hypixelFixture = http.createServer((_req, res) => {
  res.setHeader("content-type", "text/html; charset=utf-8");
  res.end("<html><body>fixture profile page</body></html>");
});

let worker;
let base;
let hypixelBase;

test.before(async () => {
  // Hermetic runs: wrangler dev persists KV/cache under .wrangler/state, which would let
  // a previous run's 6h-fresh urchin entries satisfy this run's lookups.
  const { rmSync } = await import("node:fs");
  rmSync(new URL("../.wrangler/state", import.meta.url), { recursive: true, force: true });
  rmSync(new URL("../.wrangler/state-badkey", import.meta.url), { recursive: true, force: true });
  const fixtureListening = once(fixture, "listening");
  const hypixelListening = once(hypixelFixture, "listening");
  fixture.listen(0, "0.0.0.0");
  hypixelFixture.listen(0, "0.0.0.0");
  await Promise.all([fixtureListening, hypixelListening]);
  const coralPort = fixture.address().port;
  const lan = lanAddress();
  if (!lan) throw new Error("No LAN IPv4 address available for the Coral fixture");
  hypixelBase = `http://${lan}:${hypixelFixture.address().port}`;
  const { unstable_dev } = await import("wrangler");
  worker = await unstable_dev("src/worker.js", {
    experimental: { disableExperimentalWarning: true },
    vars: {
      STATS_TOKEN: TOKEN,
      URCHIN_KEY: KEY,
      URCHIN_BASE: `http://${lan}:${coralPort}`,
      HYPIXEL_BASE: hypixelBase,
    },
    kv: [{ binding: "STATS_KV" }],
    local: true,
  });
  base = `http://${worker.address}:${worker.port}`;
});

test.after(async () => {
  if (worker) await worker.stop();
  fixture.close();
  hypixelFixture.close();
});

function req(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (opts.token !== false) headers["X-BedwarsQol-Token"] = TOKEN;
  if (opts.optIn) headers["X-BWQOL-Urchin"] = "1";
  return fetch(base + path, { method: opts.method || "GET", headers, body: opts.body });
}

// NOTE: /bedwars/<name> hits hypixel.net on cache miss; these tests use paths that don't
// need a successful scrape to prove the Urchin behavior (metadata absence/presence and
// upstream call counts are asserted regardless of stats success).

test("/health without token -> 401, no hypixel probe leverage", async () => {
  const r = await fetch(base + "/health");
  assert.equal(r.status, 401);
  const body = await r.json();
  assert.equal(body.error, "unauthorized");
});

test("/health with token -> 200 probe result with workerColo", async () => {
  const r = await req("/health");
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.ok("workerColo" in body);
});

test("no opt-in header -> zero Coral calls even with key+token (single route)", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/SomePlayer?uuid=${TAGGED}`);
  const body = await r.json();
  assert.equal(coralCalls.length, before);
  assert.ok(!("urchin" in body) && !("urchinChecked" in body) && !("urchinUnavailable" in body));
});

test("wrong token -> urchin inert on manual route", async () => {
  const r = await fetch(base + "/urchin/TaggedGuy", {
    headers: { "X-BedwarsQol-Token": "wrong", "X-BWQOL-Urchin": "1" },
  });
  assert.equal(r.status, 403);
});

test("opt-in single with uuid -> Coral called with exactly that uuid; tags attach", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/TaggedStats?uuid=${TAGGED.toUpperCase()}`, { optIn: true });
  const body = await r.json();
  const calls = coralCalls.slice(before).filter((c) => c.url === BATCH_PATH && c.method === "POST");
  assert.equal(calls.length, 1);
  assert.deepEqual(JSON.parse(calls[0].body).uuids, [TAGGED]); // canonicalized, exact set
  assert.equal(body.urchinChecked, true);
  assert.equal(body.urchin.tags[0].type, "blatant_cheater");
  assert.equal(body.urchin.tags[0].reason, "kb");
  // Unit pinned: an integer unix-ms added_on must arrive intact as addedOn (it is the age
  // text in the alert hover; a toMs regression would silently make every one of them 0).
  assert.equal(body.urchin.tags[0].addedOn, ADDED_ON_MS);
  // Data minimization: the real v3 reporter fields the fixture DOES send must all be dropped.
  for (const f of REPORTER_FIELDS) assert.ok(!(f in body.urchin.tags[0]), `leaked ${f}`);
  assert.ok(!JSON.stringify(body).includes(REPORTER.added_by_username)); // no reporter identity anywhere
});

test("opt-in single without uuid -> no Coral call, no metadata", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/NamedOnly`, { optIn: true });
  const body = await r.json();
  assert.equal(coralCalls.slice(before).filter((c) => c.url === BATCH_PATH && c.method === "POST").length, 0);
  assert.ok(!("urchinChecked" in body) && !("urchinUnavailable" in body));
});

test("opt-in single with malformed uuid -> no Coral call, no metadata", async () => {
  const before = coralCalls.length;
  const r = await req(`/bedwars/BadUuid?uuid=zzzz`, { optIn: true });
  const body = await r.json();
  assert.equal(coralCalls.slice(before).filter((c) => c.url === BATCH_PATH && c.method === "POST").length, 0);
  assert.ok(!("urchinChecked" in body));
});

test("known-empty caches: second lookup for untagged uuid makes no new Coral call", async () => {
  const before = coralCalls.length;
  await req(`/bedwars/UntaggedA?uuid=${UNTAGGED}`, { optIn: true });
  const mid = coralCalls.filter((c) => c.url === BATCH_PATH && c.method === "POST").length;
  const r2 = await req(`/bedwars/UntaggedB?uuid=${UNTAGGED}`, { optIn: true });
  const body2 = await r2.json();
  const after = coralCalls.filter((c) => c.url === BATCH_PATH && c.method === "POST").length;
  assert.equal(after, mid); // cache hit, no second upstream call
  assert.equal(body2.urchinChecked, true);
  assert.ok(!("urchin" in body2)); // known-empty -> no urchin field
});

test("manual route: tagged / empty / 404 distinctions", async () => {
  const tagged = await (await req("/urchin/TaggedGuy", { optIn: true })).json();
  assert.equal(tagged.success, true);
  assert.equal(tagged.notFound, false);
  assert.equal(tagged.tags[0].type, "sniper");
  assert.equal(tagged.tags[0].addedOn, ADDED_ON_MS);
  for (const f of REPORTER_FIELDS) assert.ok(!(f in tagged.tags[0]), `leaked ${f}`);
  assert.ok(!JSON.stringify(tagged).includes(REPORTER.added_by_username));

  const empty = await (await req("/urchin/CleanGuy", { optIn: true })).json();
  assert.equal(empty.tags.length, 0);
  assert.equal(empty.notFound, false);
  assert.equal(empty.unavailable, false);

  const nf = await (await req("/urchin/NoSuchPlayer", { optIn: true })).json();
  assert.equal(nf.notFound, true);
  assert.equal(nf.tags.length, 0);
});

test("the key travels in X-API-Key and NEVER in a URL", async () => {
  // A key in a URL is logged by proxies and leaks via Referer, so this asserts placement,
  // not merely acceptance. SELF-CONTAINED: it drives both upstream paths itself and asserts
  // only over the calls it caused, so it neither depends on earlier tests having run nor is
  // polluted by the bad-key test's deliberately-wrong key (which it previously escaped only
  // because that test happens to be declared later in the file).
  const before = coralCalls.length;
  await req(`/bedwars/KeyPlacement?uuid=${KEYCHECK}`, { optIn: true }); // POST /v3/players
  await req("/urchin/KeyPlacementGuy", { optIn: true }); // GET /v3/player/tags
  const mine = coralCalls.slice(before);
  const posts = mine.filter((c) => c.method === "POST" && c.url === BATCH_PATH);
  const gets = mine.filter((c) => c.method === "GET" && c.url === NAME_PATH);
  assert.ok(posts.length >= 1 && gets.length >= 1, "non-vacuous: both upstream paths exercised");
  for (const c of mine) {
    // Present AND correct in the header: if the key moved to the URL this would be null.
    assert.equal(c.apiKeyHeader, KEY);
    assert.equal(c.queryKey, null);
    assert.ok(!c.rawUrl.includes(KEY), `key leaked into URL: ${c.rawUrl}`);
    assert.ok(!/[?&]key=/.test(c.rawUrl));
    assert.ok(!/[?&]sources=/.test(c.rawUrl)); // v2's MANUAL filter has no v3 equivalent
  }
});

test("uuid OMITTED from the batch response -> NO urchin fields at all (client-retryable)", async () => {
  // Client-visible shape, not just the internal state: parseUrchin returns null (= retry
  // later) ONLY when a line carries no urchin field whatsoever. urchinUnavailable is NOT
  // retryable - UrchinResult.resolved() is `checked || unavailable`, so it makes StatsCache
  // mark urchinResolved sticky and the GUI show a permanent false "no tags". An omitted
  // uuid with no stale data must therefore emit nothing.
  const first = await (await req(`/bedwars/OmitOne?uuid=${OMITTED}`, { optIn: true })).json();
  for (const f of ["urchin", "urchinChecked", "urchinUnavailable", "urchinNotFound", "urchinUuid"]) {
    assert.ok(!(f in first), `omitted uuid must emit no ${f}`);
  }

  // Not cached: a second lookup must retry upstream rather than serve a fabricated "clean".
  const before = coralCalls.filter((c) => c.url === BATCH_PATH && c.method === "POST").length;
  const second = await (await req(`/bedwars/OmitTwo?uuid=${OMITTED}`, { optIn: true })).json();
  const after = coralCalls.filter((c) => c.url === BATCH_PATH && c.method === "POST").length;
  assert.equal(after, before + 1);
  for (const f of ["urchin", "urchinChecked", "urchinUnavailable"]) {
    assert.ok(!(f in second), `omitted uuid must emit no ${f} on retry either`);
  }
});

test("batch: uuids aligned with '-' placeholders; only eligible uuids reach Coral; urchinUpdate after base lines", async () => {
  const before = coralCalls.length;
  // Names resolve via cache from earlier tests or fail to scrape; either way the NDJSON
  // stream completes and the Coral body must contain ONLY the aligned non-placeholder uuid.
  const r = await req(
    `/bedwars/batch?names=AlphaOne,BetaTwo&uuids=${TAGGED},-`,
    { optIn: true }
  );
  const text = await r.text();
  const lines = text.trim().split("\n").map((l) => JSON.parse(l));
  const calls = coralCalls.slice(before).filter((c) => c.url === BATCH_PATH && c.method === "POST");
  assert.ok(calls.length <= 1);
  if (calls.length === 1) {
    assert.deepEqual(JSON.parse(calls[0].body).uuids, [TAGGED]);
  }
  // Ordering: any urchinUpdate line must come after its base line.
  const baseIdx = new Map();
  lines.forEach((l, i) => { if (!l.urchinUpdate && !l.starUpdate) baseIdx.set(l.name, i); });
  lines.forEach((l, i) => {
    if (l.urchinUpdate) assert.ok(baseIdx.has(l.name) && baseIdx.get(l.name) < i);
  });
  // The placeholder member must never carry urchin metadata.
  for (const l of lines) {
    if (l.name === "BetaTwo") assert.ok(!("urchin" in l) && !("urchinChecked" in l));
  }
});

test("key route: 409 when URCHIN_KEY secret is set; unauthorized without token; key never echoed", async () => {
  const conflict = await req("/urchin/key", {
    method: "POST",
    body: JSON.stringify({ key: "abcdefgh12345678" }),
  });
  assert.equal(conflict.status, 409);
  const cBody = await conflict.text();
  assert.ok(!cBody.includes("abcdefgh12345678"));

  const noAuth = await fetch(base + "/urchin/key", {
    method: "POST",
    body: JSON.stringify({ key: "abcdefgh12345678" }),
  });
  assert.equal(noAuth.status, 403);
  assert.ok(!(await noAuth.text()).includes("abcdefgh12345678"));
});

test("case-varied duplicates with DIFFERENT uuids fail closed: neither uuid reaches Coral", async () => {
  const before = coralCalls.length;
  const r = await req(
    `/bedwars/batch?names=CaseGuy,caseguy&uuids=${TAGGED},${UNTAGGED}`,
    { optIn: true }
  );
  const lines = (await r.text()).trim().split("\n").map((l) => JSON.parse(l));
  // Conflicting alignment cannot be attributed through the case-insensitive stream dedupe,
  // so the batch must not resolve EITHER uuid (no omission ambiguity, no cross-attachment);
  // the client's bounded single path handles each unambiguously later.
  const sent = coralCalls.slice(before).filter((c) => c.url === BATCH_PATH && c.method === "POST")
    .flatMap((c) => JSON.parse(c.body).uuids);
  assert.ok(!sent.includes(TAGGED) && !sent.includes(UNTAGGED));
  for (const l of lines) {
    assert.ok(!("urchin" in l) && !("urchinChecked" in l));
    if (l.urchinUnavailable) assert.ok(typeof l.urchinUuid === "string");
  }
});

test("same-uuid case-varied duplicates still resolve normally with urchinUuid provenance", async () => {
  const r = await req(
    `/bedwars/batch?names=SameGuy,sameguy&uuids=${TAGGED},${TAGGED}`,
    { optIn: true }
  );
  const lines = (await r.text()).trim().split("\n").map((l) => JSON.parse(l));
  const withMeta = lines.filter((l) => l.urchin || l.urchinChecked);
  assert.ok(withMeta.length >= 1); // metadata REQUIRED - the regression must not pass vacuously
  for (const l of withMeta) assert.equal(l.urchinUuid, TAGGED);
});

test("no-KV deployment: data routes are resolved-unavailable, not unauthorized", async () => {
  const { unstable_dev } = await import("wrangler");
  const lan = lanAddress();
  const w2 = await unstable_dev("src/worker.js", {
    experimental: { disableExperimentalWarning: true },
    vars: {
      STATS_TOKEN: TOKEN,
      URCHIN_KEY: KEY,
      // Point at the SAME fixture so "zero upstream calls" is observable, not structural.
      URCHIN_BASE: `http://${lan}:${fixture.address().port}`,
      HYPIXEL_BASE: hypixelBase,
    },
    local: true,
  });
  const coralBefore = coralCalls.length;
  try {
    const b2 = `http://${w2.address}:${w2.port}`;
    const h = { "X-BedwarsQol-Token": TOKEN, "X-BWQOL-Urchin": "1" };
    const manual = await (await fetch(`${b2}/urchin/SomeGuy`, { headers: h })).json();
    assert.equal(manual.success, true);
    assert.equal(manual.unavailable, true);
    const single = await (await fetch(`${b2}/bedwars/NoKvGuy?uuid=${TAGGED}`, { headers: h })).json();
    assert.equal(single.urchinUnavailable, true);
    assert.ok(!("urchin" in single));
    const batch = await (await fetch(`${b2}/bedwars/batch?names=NoKvGuy&uuids=${TAGGED}`, { headers: h })).text();
    const line = JSON.parse(batch.trim().split("\n")[0]);
    assert.equal(line.urchinUnavailable, true);
    assert.equal(coralCalls.length, coralBefore); // observable: zero Coral traffic without KV
  } finally {
    await w2.stop();
  }
});

// LAST: a rejected key arms the shared "disabled" flag, which would suppress every later
// lookup in this file. Own worker + own persistence dir so it cannot poison the others.
test("bad key -> v3 401 (empty body) -> rejected; the disable needs TWO in a row", async () => {
  const { unstable_dev } = await import("wrangler");
  const lan = lanAddress();
  const w3 = await unstable_dev("src/worker.js", {
    experimental: { disableExperimentalWarning: true },
    vars: {
      STATS_TOKEN: TOKEN,
      URCHIN_KEY: "wrong-key-0123456789",
      URCHIN_BASE: `http://${lan}:${fixture.address().port}`,
      HYPIXEL_BASE: hypixelBase,
    },
    kv: [{ binding: "STATS_KV" }],
    persistTo: ".wrangler/state-badkey",
    local: true,
  });
  try {
    const b3 = `http://${w3.address}:${w3.port}`;
    const h = { "X-BedwarsQol-Token": TOKEN, "X-BWQOL-Urchin": "1" };
    const first = await (await fetch(`${b3}/urchin/TaggedGuy`, { headers: h })).json();
    // 401 has an EMPTY body: classification must come from the status, not a parse.
    assert.equal(first.success, true);
    assert.equal(first.unavailable, true);
    assert.equal(first.tags.length, 0);
    const rejected = coralCalls.filter((c) => c.apiKeyHeader === "wrong-key-0123456789");
    assert.equal(rejected.length, 1);

    // ONE rejection must not disable: api.urchin.gg is behind Cloudflare, so a WAF 403 or a
    // 401 during an upstream deploy looks identical here and must not cost an hour of lookups.
    const second = await (await fetch(`${b3}/bedwars/BadKeyGuy?uuid=${UNTAGGED}`, { headers: h })).json();
    assert.equal(coralCalls.filter((c) => c.apiKeyHeader === "wrong-key-0123456789").length, 2);
    // Nothing resolved and nothing displayed: urchinUnavailable is sticky client-side, so a
    // rejection - which may be transient - must leave the player RETRYABLE.
    for (const f of ["urchin", "urchinChecked", "urchinUnavailable"]) assert.ok(!(f in second));

    // Two in a row IS a bad key: the disable is armed and the next lookup makes no call.
    const before = coralCalls.length;
    const third = await (await fetch(`${b3}/bedwars/BadKeyGuy2?uuid=${UNTAGGED}`, { headers: h })).json();
    assert.equal(coralCalls.length, before);
    for (const f of ["urchin", "urchinChecked", "urchinUnavailable"]) assert.ok(!(f in third));
  } finally {
    await w3.stop();
  }
});
