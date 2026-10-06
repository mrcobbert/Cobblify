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
