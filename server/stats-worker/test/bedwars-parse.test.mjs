/**
 * Pure unit tests for the hypixel.net/player Bedwars parser's per-mode guard. The forum prints all
 * four per-mode rows even for a never-played account (live pages checked 2026-09-20), so "overall
 * labels resolve, zero per-mode labels resolve" is markup drift: the body then carries
 * modesError:"parse_failed" and no modes block, and the client renders the overall numbers
 * labelled "All" instead of claiming a mode it never parsed.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { parseBedwarsFromHtml } from "../src/bedwars-parse.js";

const MODES = ["Solo", "Doubles", "3v3v3v3", "4v4v4v4"];
const STATS = ["Wins", "Losses", "Kills", "Deaths", "Final Kills", "Final Deaths"];

const row = (label, value) => `<tr><td>${label}</td><td>${value}</td></tr>`;

/** The Bedwars block, plus any HTML that follows it on the page (e.g. the next game's block). */
function block(rows, following = "") {
  return `<html><body><div id="stats-content-bedwars"><table>${rows.join("\n")}</table></div>${following}</body></html>`;
}

/** Another game's block, in the same `stats-content-<game>` shape the forum gives every game. */
function otherGame(game, value) {
  return `<div id="stats-content-${game}"><table>${STATS.map((s) => row(s, value)).join("\n")}</table></div>`;
}

/** A forum-shaped Bedwars section: overall rows, then per-mode rows with the given prefixes. */
function page(overallValue, modeValue, modePrefixes = MODES, following = "") {
  const rows = [];
  for (const s of STATS) rows.push(row(s, overallValue));
  for (const m of modePrefixes) for (const s of STATS) rows.push(row(`${m} ${s}`, modeValue));
  return block(rows, following);
}

/** The 24 per-mode rows every page carries, used when only the overall rows vary. */
function modeRows(modeValue = "250", modePrefixes = MODES) {
  const rows = [];
  for (const m of modePrefixes) for (const s of STATS) rows.push(row(`${m} ${s}`, modeValue));
  return rows;
}

test("a normal page returns overall and all four modes", () => {
  const body = parseBedwarsFromHtml(page("1,000", "250"), "P");
  assert.equal(body.success, true);
  assert.equal(body.state, "OK");
  assert.equal(body.overall.wins, 1000);
  assert.deepEqual(Object.keys(body.modes), ["solo", "doubles", "threes", "fours"]);
  assert.equal(body.modes.fours.finalKills, 250);
  assert.equal(body.modesError, undefined);
});

test("a never-played page (all labels present, all zero) still returns NEVER_PLAYED with modes", () => {
  const body = parseBedwarsFromHtml(page("0", "0"), "P");
  assert.equal(body.success, true);
  assert.equal(body.state, "NEVER_PLAYED");
  assert.deepEqual(Object.keys(body.modes), ["solo", "doubles", "threes", "fours"]);
  assert.equal(body.modesError, undefined);
});

test("overall resolving with no per-mode label resolving is reported, not served as zeros", () => {
  // Renamed per-mode labels: the overall six still parse, none of the 24 mode labels do.
  const body = parseBedwarsFromHtml(page("1,000", "250", ["Solos", "Twos", "Threes", "Fours"]), "P");
  assert.equal(body.success, true);
  assert.equal(body.state, "OK");
  assert.equal(body.overall.wins, 1000);
  assert.equal(body.modesError, "parse_failed");
  assert.equal("modes" in body, false);
});

test("a page with only overall rows is the same drift case", () => {
  const body = parseBedwarsFromHtml(page("10", "0", []), "P");
  assert.equal(body.success, true);
  assert.equal(body.modesError, "parse_failed");
  assert.equal("modes" in body, false);
});

test("a partial per-mode parse (some labels resolve) is still served", () => {
  // One mode's labels renamed: the other three resolve, so the block is served as-is.
  const html = page("1,000", "250", ["Solo", "Doubles", "3v3v3v3"]) ;
  const body = parseBedwarsFromHtml(html, "P");
  assert.equal(body.modesError, undefined);
  assert.equal(body.modes.threes.wins, 250);
  assert.equal(body.modes.fours.wins, 0);
});

test("the overall guard is unchanged: no overall label resolves -> parse_failed ERROR", () => {
  const body = parseBedwarsFromHtml('<html><body><div id="stats-content-bedwars">markup changed</div></body></html>', "P");
  assert.equal(body.success, false);
  assert.equal(body.state, "ERROR");
  assert.equal(body.error, "parse_failed");
});

// ---- W2: the OVERALL guard, the value shape, and section ownership ---------------------------
// The forum prints all six overall rows even for a never-played account (hypixel.net/player/Notch,
// fetched live 2026-09-22: all six present with "0", no empty cells in the section). So "the
// Bedwars section is here but a label did not resolve" is markup drift, not a player state - and
// serving it as zeros would cache a wrong record for 15 minutes. It must be parse_failed instead.

test("two renamed overall labels are drift, not zeros", () => {
  const rows = STATS.map((s) => row(s === "Final Kills" ? "Ultimate Kills" : s === "Final Deaths" ? "Ultimate Deaths" : s, "1,000"));
  const body = parseBedwarsFromHtml(block(rows.concat(modeRows())), "P");
  assert.equal(body.success, false);
  assert.equal(body.state, "ERROR");
  assert.equal(body.error, "parse_failed");
  assert.equal(body.finalKills, undefined, "a renamed label must never be served as 0");
});

test("a single missing overall row is drift, not zeros", () => {
  const rows = STATS.filter((s) => s !== "Losses").map((s) => row(s, "1,000"));
  const body = parseBedwarsFromHtml(block(rows.concat(modeRows())), "P");
  assert.equal(body.success, false);
  assert.equal(body.state, "ERROR");
  assert.equal(body.error, "parse_failed");
  assert.equal(body.losses, undefined);
});

test("an empty overall value cell is a missing label, not 0", () => {
  // An empty <td></td> yields no token, so the token after "Wins" is the NEXT LABEL. Reading that
  // as 0 also shifted every later value's meaning, so the whole page must be rejected.
  const rows = STATS.map((s) => (s === "Wins" ? "<tr><td>Wins</td><td></td></tr>" : row(s, "1,000")));
  const body = parseBedwarsFromHtml(block(rows.concat(modeRows())), "P");
  assert.equal(body.success, false);
  assert.equal(body.state, "ERROR");
  assert.equal(body.error, "parse_failed");
  assert.equal(body.wins, undefined, "an empty cell must never be served as wins:0");
});

test("a thousands-separated value still parses", () => {
  const body = parseBedwarsFromHtml(page("1,234", "250"), "P");
  assert.equal(body.state, "OK");
  assert.equal(body.wins, 1234);
  assert.equal(body.overall.finalDeaths, 1234);
});

test("an empty per-mode cell counts as that mode label missing, and the page is still served", () => {
  // Per-mode policy is unchanged (plan: partial per-mode parses are served): the stricter value
  // check only stops the empty cell from being read as 0 with the next label's meaning.
  const rows = STATS.map((s) => row(s, "1,000"));
  const modes = modeRows();
  modes[0] = "<tr><td>Solo Wins</td><td></td></tr>"; // Solo Wins, empty cell
  const body = parseBedwarsFromHtml(block(rows.concat(modes)), "P");
  assert.equal(body.success, true);
  assert.equal(body.state, "OK");
  assert.equal(body.modesError, undefined, "one missing per-mode label is still served");
  assert.deepEqual(Object.keys(body.modes), ["solo", "doubles", "threes", "fours"]);
  assert.equal(body.modes.solo.wins, 0);
  assert.equal(body.modes.solo.losses, 250, "the following rows keep their own values");
});

test("a missing Bed Wars label is never satisfied by the next game's table", () => {
  // Section ownership: the old flat 20 KB window reached past the Bedwars block into the next
  // `stats-content-<game>` block, whose own "Wins" row answered the missing Bedwars one.
  const rows = STATS.filter((s) => s !== "Wins").map((s) => row(s, "1,000"));
  const html = block(rows.concat(modeRows()), otherGame("skywars", "999"));
  assert.ok(html.indexOf("stats-content-skywars") - html.indexOf("stats-content-bedwars") < 20_000,
    "the following block must sit inside the old flat window for this test to mean anything");
  const body = parseBedwarsFromHtml(html, "P");
  assert.equal(body.success, false);
  assert.equal(body.state, "ERROR");
  assert.equal(body.error, "parse_failed");
  assert.equal(body.wins, undefined, "SkyWars' Wins must never be served as the Bedwars one");
});

test("a full Bed Wars block followed by another game returns the Bed Wars numbers", () => {
  const body = parseBedwarsFromHtml(page("1,000", "250", MODES, otherGame("skywars", "999")), "P");
  assert.equal(body.success, true);
  assert.equal(body.state, "OK");
  assert.equal(body.wins, 1000);
  assert.equal(body.overall.finalKills, 1000);
  assert.equal(body.modes.solo.wins, 250);
});

test("a Bed Wars block with nothing after it still parses (fallback slice)", () => {
  // No further `stats-content-` marker: the section falls back to the flat window.
  const html = page("1,000", "250");
  assert.equal(html.indexOf("stats-content-", html.indexOf("stats-content-bedwars") + 21), -1);
  const body = parseBedwarsFromHtml(html, "P");
  assert.equal(body.success, true);
  assert.equal(body.state, "OK");
  assert.equal(body.wins, 1000);
  assert.deepEqual(Object.keys(body.modes), ["solo", "doubles", "threes", "fours"]);
});
