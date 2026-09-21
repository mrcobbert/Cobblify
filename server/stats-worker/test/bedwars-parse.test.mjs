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

/** A forum-shaped Bedwars section: overall rows, then per-mode rows with the given prefixes. */
function page(overallValue, modeValue, modePrefixes = MODES) {
  const rows = [];
  for (const s of STATS) rows.push(`<tr><td>${s}</td><td>${overallValue}</td></tr>`);
  for (const m of modePrefixes) for (const s of STATS) rows.push(`<tr><td>${m} ${s}</td><td>${modeValue}</td></tr>`);
  return `<html><body><div id="stats-content-bedwars"><table>${rows.join("\n")}</table></div></body></html>`;
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
