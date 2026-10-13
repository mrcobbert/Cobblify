import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import { ACCENTS, NEW_PLAYER_THEME, themeFor } from "./ui/accent.js";

const GUI_THEME = fileURLToPath(
  new URL("../../common/src/main/java/com/bedwarsqol/gui/render/GuiTheme.java", import.meta.url),
);

test("the accent table is the game's GuiTheme.Accent, token for token", () => {
  const java = readFileSync(GUI_THEME, "utf8");
  const game = {};
  for (const m of java.matchAll(/^\s*[A-Z_]+\("([a-z-]+)", "[^"]+", 0xFF([0-9A-F]{6})\)[,;]/gm)) {
    game[m[1]] = "#" + m[2];
  }
  assert.ok(Object.keys(game).length >= 39, "parsed the enum");
  assert.deepEqual(ACCENTS, game);
});

test("a brand-new player gets Pale Blue and the Minecraft font", () => {
  assert.deepEqual(themeFor({}), { accent: "#C0DCFF", font: "minecraft" });
  assert.deepEqual(themeFor(undefined), NEW_PLAYER_THEME);
});

test("a game config is followed exactly as the game reads it", () => {
  assert.deepEqual(themeFor({ accent: "deep-teal", font: "minecraft" }), { accent: "#047664", font: "minecraft" });
  assert.deepEqual(themeFor({ accent: "pale-pink" }), { accent: "#FDC6E5", font: "modern" });
  assert.deepEqual(themeFor({ accent: "ultraviolet", font: "modern" }), { accent: "#F0770F", font: "modern" });
  assert.deepEqual(themeFor({ font: "minecraft" }), { accent: "#F0770F", font: "minecraft" });
});

// ── the Launch key is always White (TEST-NOTES 3) ─────────────────────────────────────────

import { KEY_WHITE, accentVars, keyVars } from "./ui/kit.js";

test("the Launch key's colour is the game's White, with White's own shades and ink", () => {
  assert.equal(KEY_WHITE, ACCENTS.white);
  const white = accentVars(ACCENTS.white);
  const key = keyVars();
  assert.deepEqual(Object.keys(key).sort(), Object.keys(white).map((k) => "--key" + k.slice(5)).sort());
  for (const k in white) assert.equal(key["--key" + k.slice(5)], white[k], k);
  assert.equal(key["--key-on"], "#121212", "dark ink on White");
});

test("the hero keys, their bars, the menu's tick and the gear's dot take --key*; the wool and the sheet keep --acc", () => {
  const css = readFileSync(fileURLToPath(new URL("./ui/app.css", import.meta.url)), "utf8");
  const rule = css.match(/\n(\.hero,\.caret,\.mrow \.ck,\.hdr \.key\.gear \.gdot)\{([^}]*)\}/);
  assert.ok(rule, "the White rule");
  for (const v of ["", "-hl", "-ln", "-dp", "-rim", "-sh", "-sh2", "-on"]) {
    assert.match(rule[2], new RegExp(`--acc${v}:var\\(--key${v}\\)`), v);
  }
  assert.doesNotMatch(rule[1], /\.tog|\.opts/, "the settings' toggles and options keep the accent");
});
