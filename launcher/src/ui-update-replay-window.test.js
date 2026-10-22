import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The first open after an update, when the first home is not ready (code review round 1, I1): Lunar is set up but
 * its jars could not be brought up to date (Lunar was open), so the hero is Try again. The build replay and What's
 * New wait for the first READY home of the session: once Lunar is closed and the window's focus refresh finds the
 * target ready, What's New appears. One boot per file; the tests run in order.
 */

const lunar = (o) => ({ kind: "lunar", state: "ready", message: "", action: "none", candidates: [], has_setup_folder: false, installed: true, set_up: true, opened_once: true, ...o });
const forge = { kind: "forge", state: "absent", issue: "missing_launcher", message: "", action: "none", candidates: [], has_setup_folder: false, installed: false, set_up: false, instances: [] };
const status = (l) => ({ state: "ready", message: "", mod_version: "0.17.0", targets: [l, forge] });
const BLOCKED = status(lunar({ state: "blocked", message: "Close Lunar Client so Cobblify can update its files." }));
const READY = status(lunar());

let current = BLOCKED;
const replies = {
  refresh_setup: () => current,
  setup_state: () => null,
  open_info: () => ({ version: "0.18.0-next.2", updated: true, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "valid" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  open_launcher: () => null,
};

const app = await bootWindow({ replies });
const { window, el, waitFor, tick, countCalls } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);
const hero = () => $("#main .heros .hero");

test("a blocked first home shows Try again and keeps the update's open for later", async () => {
  await waitFor(() => hero()?.textContent.includes("Try again"), { label: "the blocked home" });
  await waitFor(() => countCalls("open_info") > 0, { label: "open_info" });
  await tick(300);
  assert.equal($(".modal"), null, "no What's New over a blocked home");
});

test("the first ready home after a focus refresh plays the open: What's New appears", async () => {
  current = READY;
  const before = countCalls("refresh_setup");
  window.dispatchEvent(new window.Event("blur"));
  window.dispatchEvent(new window.Event("focus"));
  await waitFor(() => countCalls("refresh_setup") > before, { label: "the focus refresh", timeout: 3000 });
  await waitFor(() => $('#main [data-act="play"]') != null, { label: "the ready home" });
  await waitFor(() => $(".modal") != null, { label: "What's New", timeout: 3000 });
  assert.match($(".modal").getAttribute("aria-label"), /What's new in 0\.18\.0/);
  $('.modal .mfoot-band [data-act="close-modal"]').click();
  assert.equal($(".modal"), null);
});

test("it plays once: a later refresh to a ready home does not open What's New again", async () => {
  const before = countCalls("refresh_setup");
  window.dispatchEvent(new window.Event("blur"));
  window.dispatchEvent(new window.Event("focus"));
  await waitFor(() => countCalls("refresh_setup") > before, { label: "another refresh", timeout: 3000 });
  await tick(300);
  assert.equal($(".modal"), null);
});
