/**
 * Pure unit tests for the shmeado page parser against the committed fixtures (real responses,
 * 2026-07-30) plus synthetic drift mutations. The parser must be fail-closed: every ambiguity is
 * parse_failed - never wrong stats, never NICKED, never NEVER_PLAYED-by-absence - because a
 * shmeado layout change must degrade to today's blocked behavior, not to wrong numbers.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseShmeadoPlayer } from "../src/shmeado.js";

const fixture = (name) =>
  readFileSync(fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url)), "utf8");

const BEEPOR = fixture("shmeado-ok-beepor.html");
const NOTCH = fixture("shmeado-ok-notch.html");
const INVALID = fixture("shmeado-invalid-name.html");

// Beepor's real six counters, straight from the fixture (anchored-match verified in the plan).
const BEEPOR_OVERALL = {
  wins: 61, losses: 508, kills: 772, deaths: 1762, finalKills: 155, finalDeaths: 563,
};

// ---- synthetic page builder ------------------------------------------------

const SIX =
  "wins_bedwars:61,losses_bedwars:508,kills_bedwars:772,deaths_bedwars:1762," +
  "final_kills_bedwars:155,final_deaths_bedwars:563";
const SIX_ZERO =
  "wins_bedwars:0,losses_bedwars:0,kills_bedwars:0,deaths_bedwars:0," +
  "final_kills_bedwars:0,final_deaths_bedwars:0";

/** A minimal page with the real object shape: name/rank head, nested stats.bedwars, achievements. */
function page({ name = "TestGuy", rank = "rank:{rank:`None`,rankPlusColor:`None`},",
                bedwars = `{${SIX}}`, achievements = ",achievements:{bedwars_level:29}" } = {}) {
  return (
    "<html><body><script>window.player={name:`" + name + "`,uuid:`0123`," + rank +
    "stats:{general:{karma:1},bedwars:" + bedwars + "}" + achievements +
    "}</script></body></html>"
  );
}

const failed = (r) => {
  assert.equal(r.success, false);
  assert.equal(r.state, "ERROR");
  assert.equal(r.error, "parse_failed");
};

// ---- fixtures --------------------------------------------------------------

test("beepor fixture: OK, exact counters, star 29, canonical name, rank mvp_plus", () => {
  const r = parseShmeadoPlayer(BEEPOR, "beepor");
  assert.equal(r.success, true);
  assert.equal(r.state, "OK");
  assert.equal(r.displayName, "beepor");
  assert.equal(r.rank, "mvp_plus");
  assert.deepEqual(r.overall, BEEPOR_OVERALL);
  // Flat compatibility fields mirror overall exactly.
  assert.equal(r.wins, 61);
  assert.equal(r.finalKills, 155); // NOT the cause-split void(65)/entity_attack(85) collisions
  assert.equal(r.finalDeaths, 563);
  assert.equal(r.bedwarsLevel, 29);
  assert.ok(!("modes" in r), "per-mode counters do not exist on this source");
});

test("beepor fixture: requested casing differs -> same canonical displayName", () => {
  const r = parseShmeadoPlayer(BEEPOR, "BEEPOR");
  assert.equal(r.success, true);
  assert.equal(r.displayName, "beepor");
});

test("Notch fixture: zero-stat NEVER_PLAYED with explicit zeros, no star, proven rankless", () => {
  const r = parseShmeadoPlayer(NOTCH, "Notch");
  assert.equal(r.success, true);
  assert.equal(r.state, "NEVER_PLAYED");
  assert.equal(r.displayName, "Notch");
  assert.equal(r.rank, null); // `None` = present-null, proven rankless
  assert.ok("rank" in r, "the rank key must be PRESENT (null), matching the hypixel path");
  assert.deepEqual(r.overall, { wins: 0, losses: 0, kills: 0, deaths: 0, finalKills: 0, finalDeaths: 0 });
  assert.ok(!("bedwarsLevel" in r), "never-played has no bedwars_level key -> star omitted");
});

test("invalid-name fixture: exact echo of the requested name -> NICKED", () => {
  const r = parseShmeadoPlayer(INVALID, "xzqv9nope12");
  assert.equal(r.success, false);
  assert.equal(r.state, "NICKED");
  assert.equal(r.displayName, "xzqv9nope12");
});

test("invalid-name fixture: a DIFFERENT requested name is not NICKED", () => {
  failed(parseShmeadoPlayer(INVALID, "SomeoneElse"));
});

test("invalid-name fixture: a PREFIX of the echoed name must not match (exact boundaries)", () => {
  failed(parseShmeadoPlayer(INVALID, "xzqv9"));
});

// ---- identity gate ---------------------------------------------------------

test("identity gate: a page for another player is rejected before anything is emitted", () => {
  failed(parseShmeadoPlayer(BEEPOR, "Alice"));
});

test("identity gate: an object head without a name is rejected (no requested-name fallback)", () => {
  failed(parseShmeadoPlayer(
    "<script>window.player={uuid:`0123`,stats:{bedwars:{" + SIX + "}}}</script>", "TestGuy"));
});

// ---- fail-closed extraction matrix -----------------------------------------

test("all six counters explicitly zero -> NEVER_PLAYED (zeros are proven, never defaulted)", () => {
  const r = parseShmeadoPlayer(page({ bedwars: `{${SIX_ZERO}}`, achievements: "" }), "TestGuy");
  assert.equal(r.success, true);
  assert.equal(r.state, "NEVER_PLAYED");
  assert.ok(!("bedwarsLevel" in r));
});

test("one counter key missing -> parse_failed, never OK-with-zero", () => {
  const five = SIX.replace("deaths_bedwars:1762,", "");
  failed(parseShmeadoPlayer(page({ bedwars: `{${five}}` }), "TestGuy"));
});

test("duplicated counter key with a conflicting value -> parse_failed", () => {
  failed(parseShmeadoPlayer(page({ bedwars: `{${SIX},wins_bedwars:999}` }), "TestGuy"));
});

test("counters nested one level deeper (historical:{...}) -> parse_failed, not those values", () => {
  failed(parseShmeadoPlayer(page({ bedwars: `{historical:{${SIX}}}` }), "TestGuy"));
});

test("direct fields plus a nested duplicate object: nested never contributes matches", () => {
  const r = parseShmeadoPlayer(
    page({ bedwars: `{${SIX},practice:{wins_bedwars:999,final_kills_bedwars:999}}` }), "TestGuy");
  assert.equal(r.success, true);
  assert.deepEqual(r.overall, BEEPOR_OVERALL);
});

test("counter text inside a backtick string never contributes (string-context anchors)", () => {
  const r = parseShmeadoPlayer(
    page({ bedwars: "{table:`{wins_bedwars:999,final_kills_bedwars:999}`," + SIX + "}" }), "TestGuy");
  assert.equal(r.success, true);
  assert.deepEqual(r.overall, BEEPOR_OVERALL);
});

test("quoted keys (a JSON-ification redesign) -> parse_failed", () => {
  const quoted = SIX.replace(/(\w+):/g, '"$1":');
  failed(parseShmeadoPlayer(page({ bedwars: `{${quoted}}` }), "TestGuy"));
});

test("renamed/absent bedwars container -> parse_failed, NEVER never-played", () => {
  const r = parseShmeadoPlayer(
    page({ bedwars: `{${SIX}}` }).replace("bedwars:{", "bedwars_v2:{"), "TestGuy");
  failed(r);
});

test("two window.player assignments -> parse_failed", () => {
  failed(parseShmeadoPlayer(page() + "<script>window.player={name:`TestGuy`}</script>", "TestGuy"));
});

test("truncated object (unterminated brace scan) -> parse_failed", () => {
  const p = page();
  failed(parseShmeadoPlayer(p.slice(0, p.indexOf("final_deaths")), "TestGuy"));
});

test("generic error page without the name echo (API hiccup stand-in) -> parse_failed", () => {
  failed(parseShmeadoPlayer("<html><body><h2>Something went wrong :(</h2></body></html>", "TestGuy"));
});

// ---- rank map --------------------------------------------------------------

test("rank map covers exactly the proven forum codes", () => {
  const cases = [
    ["None", null], ["VIP", "vip"], ["VIP+", "vip_plus"], ["MVP", "mvp"],
    ["MVP+", "mvp_plus"], ["MVP++", "superstar"], ["YOUTUBE", "youtuber"],
  ];
  for (const [raw, code] of cases) {
    const r = parseShmeadoPlayer(page({ rank: "rank:{rank:`" + raw + "`,rankPlusColor:`None`}," }), "TestGuy");
    assert.equal(r.success, true, raw);
    assert.equal(r.rank, code, raw);
  }
});

test("an unmapped rank fails the WHOLE lookup (denick safety for already-shipped clients)", () => {
  for (const raw of ["PIG+++", "ADMIN", "constructor", "garbage"]) {
    failed(parseShmeadoPlayer(page({ rank: "rank:{rank:`" + raw + "`}," }), "TestGuy"));
  }
});

test("a malformed rank structure fails the lookup; a fully absent one is proven rankless", () => {
  failed(parseShmeadoPlayer(page({ rank: "rank:7," }), "TestGuy"));
  const r = parseShmeadoPlayer(page({ rank: "" }), "TestGuy");
  assert.equal(r.success, true);
  assert.equal(r.rank, null);
});

// ---- star ------------------------------------------------------------------

test("duplicate bedwars_level -> star omitted (never wrong-but-present), lookup still OK", () => {
  const r = parseShmeadoPlayer(
    page({ achievements: ",achievements:{bedwars_level:29},legacy:{bedwars_level:31}" }), "TestGuy");
  assert.equal(r.success, true);
  assert.ok(!("bedwarsLevel" in r));
});

test("bedwars_level:0 and absent level on a played account -> star omitted, still OK", () => {
  const zero = parseShmeadoPlayer(page({ achievements: ",achievements:{bedwars_level:0}" }), "TestGuy");
  assert.equal(zero.success, true);
  assert.ok(!("bedwarsLevel" in zero));
  const absent = parseShmeadoPlayer(page({ achievements: "" }), "TestGuy");
  assert.equal(absent.success, true);
  assert.equal(absent.state, "OK");
  assert.ok(!("bedwarsLevel" in absent));
});
