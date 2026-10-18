import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The 0.18 home in the real window (index.html + main.js under JSDOM): both clients set up, Prism remembered from
 * the last run, and the first open after an update. One boot per file; the tests run in order.
 */

const PREFS = { useExternalOverlay: true, health: "valid" };
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
  outcome: { useExternalOverlay: true, action: "game_launch_requested" },
  generation: 1,
};

let pendingLaunch = null;
/** What lunar_version_ready answers while "Set Lunar to 1.8.9" shows. */
let versionReady = "other";
/** What launch_progress answers; a test swaps it to end a launch with an outcome. */
let progressReply = () => ({ stage: "attached" });
const replies = {
  refresh_setup: () => BOTH_READY,
  open_info: () => ({ version: "0.18.0-next.2", updated: true, fresh: false }),
  game_theme: ({ kind }) => (kind === "forge" ? { accent: "deep-teal", font: "modern" } : { accent: "pale-pink", font: "minecraft" }),
  launch_preferences: () => PREFS,
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: false, updateChannel: "stable", health: "missing" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  set_use_external_overlay: ({ enabled }) => ({ status: "saved", useExternalOverlay: enabled }),
  launch_lunar: () => new Promise((resolve) => (pendingLaunch = resolve)),
  launch_forge: () => new Promise((resolve) => (pendingLaunch = resolve)),
  launch_progress: () => progressReply(),
  lobby_state: () => ({ kind: "unavailable" }),
  abort_launch_session: () => ({ status: "ok", preferences: PREFS }),
  reset_session_end: () => ({ status: "ok", preferences: PREFS }),
  open_launcher: () => null,
  lunar_version_ready: () => versionReady,
};

const app = await bootWindow({
  replies,
  setup: (w) => w.localStorage.setItem("cobblify.client", "prism"),
});
const { window, document, el, waitFor, calls, tick } = app;
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

test("the settings sheet: its rows, a switch that saves, Esc returns to Settings beside Launch", async () => {
  assert.equal($(".hdr [data-act='settings']"), null);
  assert.equal($(".hdr .ver"), null, "version belongs in the side sheet");
  assert.ok($(".launch-row .heros"));
  assert.ok($(".launch-row [data-act='settings']"), "Settings is alongside the launch action");
  $('[data-act="settings"]').click();
  const sheet = el("sheet");
  await waitFor(() => sheet.classList.contains("open"), { label: "the sheet" });
  const labels = [...sheet.querySelectorAll(".srow .lbl > .t:first-child")].map((t) => t.textContent);
  assert.deepEqual(labels, ["External Overlay", "Auto-update", "Dev builds", "Cobblify 0.18.0-next.2", "Lunar Client", "Prism Launcher"]);
  assert.equal(sheet.querySelector('[data-row="lunar"] .key').textContent.trim(), "Repair");
  assert.equal(sheet.querySelector('[data-row="prism"] .key').textContent.trim(), "Change", "Prism is set up: Change re-picks its instance");
  assert.equal(sheet.querySelector('.tog[data-key="auto"]').getAttribute("aria-checked"), "true", "auto-update is on until chosen");
  assert.match(sheet.textContent, /Not an official Minecraft product/);

  sheet.querySelector('.tog[data-key="overlay"]').click();
  await waitFor(() => calls.some((c) => c.command === "set_use_external_overlay" && c.args.enabled === false), { label: "the overlay save" });
  await waitFor(() => sheet.querySelector('.tog[data-key="overlay"]').getAttribute("aria-checked") === "false", { label: "the switch off" });

  document.body.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  assert.equal(sheet.classList.contains("open"), false);
  assert.equal(document.activeElement, $('[data-act="settings"]'));
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
  assert.equal($("#main .keys2"), null, "no Cancel: Cobblify can't stop Lunar or Prism");
  replies.lobby_state = () => ({ kind: "session_ended", reason: "game_session_ended" });
  await waitFor(() => $('#main [data-act="play"]') != null, { label: "home again once the game ended" });
  replies.lobby_state = () => ({ kind: "unavailable" });
});

test("the version in Settings opens release notes; Back and Escape return to Settings", async () => {
  $('[data-act="settings"]').click();
  $('#sheet [data-act="history"]').click();
  await waitFor(() => $(".hist-list") != null, { label: "the history" });
  assert.ok(home.querySelectorAll(".hist-list .row").length >= 2);
  assert.ok($("#rel-0_18_0"));
  $('[data-act="back"]').click();
  assert.ok($('#main [data-act="play"]'));
  assert.equal(el("sheet").classList.contains("open"), true);
  $('#sheet [data-act="history"]').click();
  await waitFor(() => $(".hist-list") != null, { label: "history again" });
  document.body.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  assert.equal(el("sheet").classList.contains("open"), true);
  $('#sheet [data-act="close"]').click();
});

// ── a launch that ends with an outcome (progress.rs `outcome`) ─────────────────────────────

const hero = () => $("#main .hero");
const launchNow = async (generation) => {
  const before = calls.filter((c) => c.command === "launch_lunar").length;
  document.body.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
  await waitFor(() => calls.filter((c) => c.command === "launch_lunar").length > before, { label: "the launch call" });
  pendingLaunch({ ...LAUNCHED, generation });
};

test("Cobblify didn't load: the launch ends on home with Try again, released through the abort", async () => {
  progressReply = () => ({ stage: "mixing", percent: 85, outcome: "not_loaded" });
  const aborts = calls.filter((c) => c.command === "abort_launch_session").length;
  await launchNow(2);
  await waitFor(() => hero()?.dataset.act === "play" && /Try again/.test(hero().textContent), {
    label: "Try again",
    timeout: 3000,
  });
  assert.equal(calls.filter((c) => c.command === "abort_launch_session").length, aborts + 1);
  assert.equal($("#main .hero.prog"), null, "no progress, and never a Join Hypixel wait");
  const row = $("#main .heros .under.wait");
  assert.equal(row?.textContent.trim(), "Cobblify didn't load", "the reason under Try again");
  assert.equal(row.querySelector(".key"), null, "with nothing to press");
  assert.equal($("#main .sr").textContent, "Cobblify didn't load. Minecraft started without Cobblify.");
  assert.equal(calls.some((c) => c.command === "launch_lunar" && "expectedAutoJoinHypixel" in (c.args ?? {})), false);
  assert.deepEqual(calls.filter((c) => c.command === "launch_lunar").at(-1).args, { expectedUseExternalOverlay: true });
});

test("wrong Lunar version: Try again relaunches and the launch ends on the Launch key", async () => {
  progressReply = () => ({ stage: "attached", percent: 35, outcome: "wrong_version" });
  hero().click();
  await waitFor(() => pendingLaunch != null && calls.filter((c) => c.command === "launch_lunar").length >= 3, { label: "the retry" });
  pendingLaunch({ ...LAUNCHED, generation: 3 });
  await waitFor(() => $('#main .hero.play')?.getAttribute("aria-label") === "Launch Cobblify in Lunar Client", {
    label: "Launch again",
    timeout: 3000,
  });
  const row = $("#main .heros .under.wait");
  assert.equal(row?.querySelector(".t").textContent, "Set Lunar to 1.8.9");
  row.querySelector('[data-act="open-client"]').click();
  await waitFor(() => calls.some((c) => c.command === "open_launcher"), { label: "Lunar opened" });
  assert.deepEqual(calls.filter((c) => c.command === "open_launcher").at(-1).args, { kind: "lunar" });
  progressReply = () => ({ stage: "attached" });
});

test("Set Lunar to 1.8.9: once Lunar is switched to 1.8.9 with no game running, the launch goes on by itself", async () => {
  await waitFor(() => calls.some((c) => c.command === "lunar_version_ready"), { label: "watching Lunar", timeout: 3000 });
  const before = calls.filter((c) => c.command === "launch_lunar").length;
  versionReady = "ready";
  const looks = calls.filter((c) => c.command === "lunar_version_ready").length;
  await waitFor(() => calls.filter((c) => c.command === "lunar_version_ready").length >= looks + 2, { label: "two looks", timeout: 4000 });
  assert.equal(calls.filter((c) => c.command === "launch_lunar").length, before, "not on the first ready looks");
  await waitFor(() => calls.filter((c) => c.command === "launch_lunar").length > before, {
    label: "the launch going on",
    timeout: 4000,
  });
  pendingLaunch({ ...LAUNCHED, generation: 4 });
  progressReply = () => ({ stage: "mixing", percent: 85, outcome: "not_loaded" });
  await waitFor(() => /Try again/.test(hero()?.textContent ?? ""), { label: "the launch ended", timeout: 3000 });
  progressReply = () => ({ stage: "attached" });
});

test("Lunar surely on another version: Launch asks for 1.8.9 first, opening nothing, and never relaunches on its own at first sight", async () => {
  const aborts = calls.filter((c) => c.command === "abort_launch_session").length;
  const progress = calls.filter((c) => c.command === "launch_progress").length;
  hero().click();
  await waitFor(() => pendingLaunch != null && calls.filter((c) => c.command === "launch_lunar").length >= 5, { label: "the launch call" });
  pendingLaunch({ status: "rejected", code: "wrong_version" });
  await waitFor(() => $("#main .heros .under.wait .t")?.textContent === "Set Lunar to 1.8.9", { label: "the row", timeout: 3000 });
  assert.equal($('#main .hero.play')?.getAttribute("aria-label"), "Launch Cobblify in Lunar Client", "Launch stays");
  assert.equal(calls.filter((c) => c.command === "abort_launch_session").length, aborts, "no session to end");
  assert.equal(calls.filter((c) => c.command === "launch_progress").length, progress, "nothing launched");
  // Ready at the first look (versionReady is still true) is not a change: no relaunch.
  const launches = calls.filter((c) => c.command === "launch_lunar").length;
  const looks = calls.filter((c) => c.command === "lunar_version_ready").length;
  await waitFor(() => calls.filter((c) => c.command === "lunar_version_ready").length >= looks + 2, { label: "two looks", timeout: 4000 });
  assert.equal(calls.filter((c) => c.command === "launch_lunar").length, launches);
});

test("Set Lunar to 1.8.9: a Lunar game seen while waiting ends the wait for good", async () => {
  const launches = calls.filter((c) => c.command === "launch_lunar").length;
  hero().click();
  await waitFor(() => calls.filter((c) => c.command === "launch_lunar").length > launches, { label: "the launch call" });
  pendingLaunch({ status: "rejected", code: "wrong_version" });
  versionReady = "other";
  await waitFor(() => $("#main .heros .under.wait .t")?.textContent === "Set Lunar to 1.8.9", { label: "the row", timeout: 3000 });
  const looks = () => calls.filter((c) => c.command === "lunar_version_ready").length;
  const first = looks();
  await waitFor(() => looks() > first, { label: "a look", timeout: 3000 });
  versionReady = "game";
  const atGame = looks();
  await waitFor(() => looks() > atGame, { label: "the game seen", timeout: 3000 });
  versionReady = "ready";
  const after = looks();
  // The harness's own wait: the global setTimeout is the app's, unref'd, and would let Node end
  // the test before it runs (Windows did).
  await tick(2500);
  assert.equal(looks(), after, "no more looks");
  assert.equal(calls.filter((c) => c.command === "launch_lunar").length, launches + 1, "nothing relaunched");
});
