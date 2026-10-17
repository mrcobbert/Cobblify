import test from "node:test";
import assert from "node:assert/strict";

import { LIM, NOTES_MIN, RESERVE, boardBox, fill, floorK, frame, kFor, kForWidth, reserveSpec } from "./board-layout.js";

// The reference roster's numbers at the tight spacing, in GUI px (Minecraft font, measured from the approved design).
const GAME = { core: 330, notes: 90, h: 214 };
const LIST = { core: 300, notes: 110 };

test("the floor is GUI scale 2 in device px", () => {
  assert.equal(floorK(1), 2);
  assert.equal(floorK(1.25), 2);
  assert.equal(floorK(1.5), 3);
  assert.equal(floorK(2), 4);
});

test("k is the largest whole scale at which the worst game fits, with a presence word, never below the floor", () => {
  const f = frame(900, 520, 2);
  const k = kFor(GAME.core, GAME.h, f, 1);
  assert.ok(Number.isInteger(k));
  assert.ok(((GAME.core + NOTES_MIN) * k) / 1 <= f.aw && GAME.h * k <= f.ah, "it fits");
  assert.ok((GAME.core + NOTES_MIN) * (k + 1) > f.aw || GAME.h * (k + 1) > f.ah, "one step more does not");
  assert.equal(kFor(GAME.core, GAME.h, frame(640, 480, 2), 1), 2, "the floor at the minimum window");
  assert.ok(kFor(GAME.core, GAME.h, frame(1920, 1080, 2), 1) > k, "a bigger window reads bigger");
});

test("the board starts under the header: 24 su, or 36 su with the macOS window-button strip", () => {
  assert.equal(frame(900, 520, 2).top, 48);
  const mac = frame(900, 520, 2, 36);
  assert.equal(mac.top, 72);
  assert.equal(mac.ah, 520 - 72 - 20);
});

test("a window k depends only on the window, never on the live roster", () => {
  const f = frame(1280, 720, 2);
  assert.equal(kFor(GAME.core, GAME.h, f, 1), kFor(GAME.core, GAME.h, f, 1));
});

test("a roster wider than the reference steps k down rather than clip, to the floor at most", () => {
  const f = frame(1920, 1080, 2);
  const k = kFor(GAME.core, GAME.h, f, 1);
  assert.equal(kForWidth(k, GAME.core, f, 1), k);
  const wide = kForWidth(k, 600, f, 1);
  assert.ok(wide < k && 600 * wide <= f.aw);
  assert.equal(kForWidth(k, 5000, f, 1), 2);
});

test("the fill stays in whole GUI px within its caps and never makes the board taller than the room", () => {
  for (const [W, H] of [[640, 480], [900, 520], [1280, 720], [1920, 1080], [960, 1080], [2560, 1440]]) {
    const f = frame(W, H, 2);
    const k = kFor(GAME.core, GAME.h, f, 1);
    const spec = { game: true, h: GAME.h, L: 22, G: 8 };
    const { vars, H: room } = fill(spec, GAME, k, f, 1);
    for (const v of Object.values(vars)) assert.ok(Number.isInteger(v) && v >= 0);
    assert.ok(vars.e <= LIM.e && vars.g <= vars.e + LIM.gPlus && vars.gf <= LIM.gf && vars.gg <= LIM.gg);
    assert.ok(vars.gs <= LIM.rounds && vars.gn === vars.gs);
    if (vars.e > 0) assert.ok(spec.h + spec.L * vars.e + spec.G * vars.g <= room, `${W}x${H} fits its height`);
  }
});

test("the gutter only widens in a game", () => {
  const f = frame(1920, 1080, 2);
  const list = fill({ game: false, ...reserveSpec({ h: 120, L: 12, G: 1 }, "LOBBY", 10) }, LIST, 3, f, 1);
  assert.equal(list.vars.gg, 0);
  const game = fill({ game: true, h: GAME.h, L: 22, G: 8 }, GAME, 3, f, 1);
  assert.ok(game.vars.gg > 0);
});

test("a list is laid out for its reserve, not its live count", () => {
  const base = (rows) => ({ h: 30 + 9 * rows, L: 3 + rows, G: 1 });
  const a = reserveSpec(base(4), "LOBBY", 4);
  const b = reserveSpec(base(11), "LOBBY", 11);
  assert.deepEqual(a, b);
  assert.equal(a.L, 3 + RESERVE.LOBBY);
  assert.deepEqual(reserveSpec(base(30), "QUEUE", 30), reserveSpec(base(2), "QUEUE", 2));
  assert.deepEqual(reserveSpec({ h: 50, L: 5, G: 2 }, "GAME", 5), { h: 50, L: 5, G: 2 });
});

test("the board sits top-left on the wordmark's line and scrolls only when it must", () => {
  const f = frame(900, 520, 2);
  const fits = boardBox(f, 600, 300, 2, 1);
  assert.equal(fits.x, 22 - 4, "text on the 11 su line, the panel padding outside it");
  assert.equal(fits.y, 10);
  assert.equal(fits.over, false);
  const tall = boardBox(f, 600, 900, 2, 1);
  assert.equal(tall.over, true);
  assert.equal(tall.maxH, 520 - 48 - 10 - 2, "the bottom margin gives way to 1 su");
  const wide = boardBox(f, 890, 300, 2, 1);
  assert.equal(wide.x, 10, "a board too wide slides left");
});

test("positions land on whole device px at fractional DPR", () => {
  const f = frame(1536, 864, 2.4);
  const box = boardBox(f, 700, 300, 3 / 1.25, 1.25);
  assert.equal(Math.round(box.x * 1.25), box.x * 1.25);
  assert.equal(Math.round(box.y * 1.25), box.y * 1.25);
});
