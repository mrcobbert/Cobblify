import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { boardRow, boardView, rowCount } from "./board-model.js";
import { PREVIEW_LOBBY } from "./board-fixtures.js";
import { CONTRACT_VERSION } from "./lobby-validator.js";
import { headerHTML, rosterHTML, subHTML } from "./board-render.js";

test("the preview fixtures are current-contract snapshots (the board, not the old-mod note)", () => {
  for (const [key, snap] of Object.entries(PREVIEW_LOBBY)) {
    if (key === "outdated") assert.ok(snap.v < CONTRACT_VERSION);
    else assert.equal(snap.v, CONTRACT_VERSION, key);
  }
});

test("lobby: the sweat-ordered lobby, then Your Party with its count", () => {
  const v = boardView(PREVIEW_LOBBY.lobby);
  assert.equal(v.title, "Main Lobby");
  assert.equal(v.sub, "· 12 players");
  assert.deepEqual(v.blocks.map((b) => [b.kind, b.head, b.note]), [["section", "Lobby", "12"], ["party", "Your Party", "2"]]);
  assert.equal(v.blocks[0].rows[0].name, "xXShadowSlayerXx");
  assert.equal(rowCount(v), 14);
});

test("queue: the Chatted head stays, its helper note and foot line are gone", () => {
  const v = boardView(PREVIEW_LOBBY.queueFours);
  assert.equal(v.title, "4v4v4v4 Queue");
  assert.equal(v.sub, "· 6 parties · 16 known");
  assert.equal(v.blocks[0].head, "Chatted");
  assert.equal(v.blocks[0].note, null);
  assert.doesNotMatch(JSON.stringify(v), /only typed players resolve|stay hidden by Hypixel/);
});

test("game: opponent teams in standing order with the Target first, then your party and your team", () => {
  const v = boardView(PREVIEW_LOBBY.gameFours);
  assert.equal(v.title, "4v4v4v4");
  assert.equal(v.sub, "· 4 teams");
  assert.equal(v.ownTeam, "Blue");
  assert.deepEqual(v.blocks.map((b) => [b.team ?? b.head, b.standing ?? null, b.target ?? null]), [
    ["Red", "active", true],
    ["Green", "active", false],
    ["Yellow", "eliminated", false],
    ["Your Party", null, null],
  ]);
});

test("a row carries the mod's decisions verbatim: tier, cheater, chips, cells, tag codes", () => {
  const shadow = boardView(PREVIEW_LOBBY.lobby).blocks[0].rows[0];
  assert.equal(shadow.tier, 3);
  assert.equal(shadow.cheater, true);
  assert.deepEqual(shadow.rank, [{ c: "6", t: "[MVP" }, { c: "c", t: "++" }, { c: "6", t: "]" }]);
  assert.deepEqual(shadow.cells.map((c) => c.text), ["22.70", "14.00", "71,200", "8.90"]);
  assert.deepEqual(shadow.tags.map((t) => t.code), ["BC", "BL"]);
  const resolving = boardRow({ name: "lag", state: "LOADING" });
  assert.deepEqual(resolving.cells.map((c) => c.text), [null, null, null, null]);
  const nicked = boardRow({ name: "n", state: "NICKED" });
  assert.deepEqual(nicked.cells.map((c) => c.text), ["?", "?", "?", "?"]);
  assert.deepEqual(boardRow({ name: "p", state: "OK", presence: "ELIMINATED" }).eliminated, true);
});

test("one line per player; state words are the cells' hover title; the badge's tag is not repeated in the notes", () => {
  const html = rosterHTML(boardView(PREVIEW_LOBBY.lobby));
  assert.equal((html.match(/class="c mc[^"]*\bpr\b/g) ?? []).length, 14, "one name cell per player");
  assert.match(html, /title="resolving"/);
  assert.match(html, /title="never played"/);
  assert.match(html, /title="Nicked"/);
  const visible = html.replace(/<span class="sr">[^<]*<\/span>/g, "");
  assert.doesNotMatch(visible, />resolving<|>never played<|>Nicked</, "no state words on the rows");
  assert.match(html, /<span class="sr">Nicked<\/span>/, "screen readers still hear the state");
  // xXShadowSlayerXx: BC is the badge (a filled tile, the mod's cheater), BL stays in the notes
  assert.match(html, /class="ftile[^"]*"[^>]*title="Blatant Cheater">BC</);
  assert.match(html, /<span class="g"[^>]*>BL</);
  assert.doesNotMatch(html, /<span class="g"[^>]*>BC</);
});

test("the header shows the title and the sub without its separator", () => {
  assert.equal(headerHTML({ title: "4v4v4v4", sub: "· 4 teams" }), '<span class="t">4v4v4v4</span><span class="u">4 teams</span>');
  assert.match(headerHTML({ title: "<b>", sub: "" }), /&lt;b&gt;/);
});

test("Returning home… steps its dots; the screen reader still reads one ellipsis", () => {
  const html = subHTML("Returning home…");
  assert.match(html, /^Returning home<span class="sr">…<\/span>/);
  assert.equal((html.match(/class="dot d\d" aria-hidden="true"/g) ?? []).length, 3);
  assert.equal(subHTML("Close the game, then try again."), "Close the game, then try again.");
});

// inventory.md 9.6: Try Again sat inside the click-through shell with nothing turning clicks back on.
test("Try Again takes the mouse: the shell is click-through, the action slot is not", () => {
  const css = readFileSync(new URL("./dash.css", import.meta.url), "utf8");
  const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");
  assert.match(css, /\.stage \.joining \{[^}]*pointer-events: none/);
  assert.match(css, /\.stage \.msg \.act \{[^}]*pointer-events: auto/);
  assert.match(html, /<div class="act" id="conn-act" hidden>\s*<button class="key acc hero" id="conn-action"/);
});
