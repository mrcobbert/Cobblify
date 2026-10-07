import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The 0.18 home in the real window (index.html + main.js under JSDOM): both clients set up, Prism remembered from
 * the last run, and the first open after an update. One boot per file; the tests run in order.
 */

const PREFS = { autoJoinHypixel: true, useExternalOverlay: true, health: "valid" };
const BOTH_READY = {
  state: "ready",
  mod_version: "0.17.0",
  targets: [
    { kind: "lunar", state: "ready", label: "Lunar Client" },
    { kind: "forge", state: "ready", label: "Prism" },
  ],
};
const LAUNCHED = {
  status: "launched",
  outcome: { autoJoinHypixel: true, useExternalOverlay: true, action: "game_launch_requested" },
  generation: 1,
};

let pendingLaunch = null;
const replies = {
  refresh_setup: () => BOTH_READY,
  open_info: () => ({ version: "0.18.0-next.2", updated: true, fresh: false }),
  game_theme: ({ kind }) => (kind === "forge" ? { accent: "deep-teal", font: "modern" } : { accent: "pale-pink", font: "minecraft" }),
  launch_preferences: () => PREFS,
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: false, updateChannel: "stable", health: "missing" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  set_use_external_overlay: ({ enabled }) => ({ status: "saved", autoJoinHypixel: true, useExternalOverlay: enabled }),
  launch_lunar: () => new Promise((resolve) => (pendingLaunch = resolve)),
  launch_forge: () => new Promise((resolve) => (pendingLaunch = resolve)),
  launch_progress: () => ({ stage: "attached" }),
  lobby_state: () => ({ kind: "unavailable" }),
  abort_launch_session: () => ({ status: "ok", preferences: PREFS }),
};

const app = await bootWindow({
  replies,
  setup: (w) => w.localStorage.setItem("cobblify.client", "prism"),
});
const { window, document, el, waitFor, calls } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);
const root = document.documentElement;

await waitFor(() => $('#main [data-act="play"]') != null, { label: "the ready home" });

test("What's New opens once after an update, for the running version, and Continue closes it", async () => {
  await waitFor(() => $(".modal") != null, { label: "What's New" });
  assert.match($(".modal").getAttribute("aria-label"), /What's new in 0\.18\.0/);
  assert.ok($(".modal .wn li"), "it lists the release's notes");
  assert.match($(".modal .wn").textContent, /next/);
  $('.modal .mfoot-band [data-act="close-modal"]').click();
  assert.equal($(".modal"), null);
});

test("the remembered client is the one Launch starts, and the theme follows its game config", async () => {
  assert.equal($('#main [data-act="play"]').getAttribute("aria-label"), "Launch Cobblify in Prism Launcher");
  await waitFor(() => root.style.getPropertyValue("--acc").toLowerCase() === "#047664", { label: "Prism's accent" });
  assert.equal(root.dataset.font, "modern");
  assert.ok(calls.some((c) => c.command === "game_theme" && c.args.kind === "forge"));
});

test("the client menu: both clients, the current one ticked, keyboard, Esc back to the arrow", async () => {
  const caret = $(".caret");
  caret.click();
  const rows = () => [...home.querySelectorAll("#layer .menu .mrow")];
  await waitFor(() => rows().length === 2, { label: "the open menu" });
  assert.equal(caret.getAttribute("aria-expanded"), "true");
  assert.deepEqual(rows().map((r) => r.getAttribute("aria-checked")), ["false", "true"]);
  assert.equal(home.querySelector("#layer .madd"), null, "nothing to add with both set up");
  assert.equal(document.activeElement, rows()[1], "focus starts on the ticked client");

  const key = (k) => document.activeElement.dispatchEvent(new window.KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true }));
  key("ArrowDown");
  assert.equal(document.activeElement, rows()[0], "Down wraps to the first row");
  key("End");
  assert.equal(document.activeElement, rows()[1]);
  key("Home");
  assert.equal(document.activeElement, rows()[0]);
  key("Escape");
  assert.equal(rows().length, 0, "Esc closes it");
  assert.equal(document.activeElement, $(".caret"), "and returns to the arrow");
});

test("choosing Lunar in the menu switches Launch to it and remembers it", async () => {
  $(".caret").click();
  await waitFor(() => home.querySelector('#layer .mrow[data-id="lunar"]') != null, { label: "the menu" });
  home.querySelector('#layer .mrow[data-id="lunar"]').click();
  await waitFor(() => $('#main [data-act="play"]').getAttribute("aria-label") === "Launch Cobblify in Lunar Client", { label: "Lunar selected" });
  assert.equal(window.localStorage.getItem("cobblify.client"), "lunar");
  await waitFor(() => root.style.getPropertyValue("--acc").toLowerCase() === "#fdc6e5", { label: "Lunar's accent" });
});

test("the settings sheet: its rows, a switch that saves, Esc closes", async () => {
  $('[data-act="settings"]').click();
  const sheet = el("sheet");
  await waitFor(() => sheet.classList.contains("open"), { label: "the sheet" });
  const labels = [...sheet.querySelectorAll(".srow .lbl > .t:first-child")].map((t) => t.textContent);
  assert.deepEqual(labels, ["External Overlay", "Auto-update", "Dev builds", "Cobblify 0.18.0-next.2", "Lunar Client"]);
  assert.equal(sheet.querySelector('.tog[data-key="auto"]').getAttribute("aria-checked"), "true", "auto-update is on until chosen");
  assert.match(sheet.textContent, /Not an official Minecraft product/);

  sheet.querySelector('.tog[data-key="overlay"]').click();
  await waitFor(() => calls.some((c) => c.command === "set_use_external_overlay" && c.args.enabled === false), { label: "the overlay save" });
  await waitFor(() => sheet.querySelector('.tog[data-key="overlay"]').getAttribute("aria-checked") === "false", { label: "the switch off" });

  document.body.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  assert.equal(sheet.classList.contains("open"), false);
});

test("while launching, Launch is the progress and the sheet's launch settings are locked", async () => {
  document.body.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await waitFor(() => $("#main .hero.prog") != null, { label: "the progress key" });
  assert.match($("#main .hero.prog").getAttribute("aria-label"), /Opening Lunar/);
  assert.ok(calls.some((c) => c.command === "launch_lunar"), "Enter launched the selected client");

  $('[data-act="settings"]').click();
  const sheet = el("sheet");
  await waitFor(() => sheet.querySelector(".reason") != null, { label: "the locked reason" });
  assert.equal(sheet.querySelector('.tog[data-key="overlay"]').disabled, true);
  assert.equal(sheet.querySelector('.tog[data-key="dev"]').disabled, true);
  assert.equal(sheet.querySelector('.tog[data-key="auto"]').disabled, false);
  sheet.querySelector('[data-act="close"]').click();

  pendingLaunch(LAUNCHED);
  await waitFor(() => $("#main .hero.prog")?.getAttribute("aria-label").startsWith("Starting Minecraft"), { label: "the next phase", timeout: 3000 });
  await waitFor(() => $('#main .keys2 [data-act="cancel"]') != null, { label: "Cancel" });
  $('#main .keys2 [data-act="cancel"]').click();
  await waitFor(() => $('#main [data-act="play"]') != null, { label: "home again" });
});

test("the version opens the release notes; Back returns home", async () => {
  $(".ver").click();
  await waitFor(() => $(".hist-list") != null, { label: "the history" });
  assert.ok(home.querySelectorAll(".hist-list .row").length >= 2);
  assert.ok($("#rel-0_18_0"));
  $('[data-act="back"]').click();
  assert.ok($('#main [data-act="play"]'));
});
