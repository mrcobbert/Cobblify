import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * Status replies are ordered by the backend's revision (code review round 1, I2): a refresh that read the clients
 * before a newer publish answers with an older `rev`, and the window ignores it instead of going back to the older
 * home. Here the boot refresh is slow and reads Lunar before its setup was registered (rev 1, not set up); a later
 * refresh answers first with the set-up, ready Lunar (rev 2); the slow reply then lands. One boot per file.
 */

const lunar = (o) => ({ kind: "lunar", state: "ready", message: "", action: "none", candidates: [], has_setup_folder: false, installed: true, set_up: true, opened_once: true, ...o });
const forge = { kind: "forge", state: "absent", issue: "missing_launcher", message: "", action: "none", candidates: [], has_setup_folder: false, installed: false, set_up: false, instances: [] };
const OLD = { state: "blocked", message: "Setup needed.", mod_version: "0.17.0", rev: 1, targets: [lunar({ state: "absent", set_up: false }), forge] };
const NEW = { state: "ready", message: "", mod_version: "0.17.0", rev: 2, targets: [lunar(), forge] };

let releaseSlow = null;
let refreshes = 0;
const replies = {
  refresh_setup: () => (++refreshes === 1 ? new Promise((resolve) => (releaseSlow = () => resolve(OLD))) : NEW),
  setup_state: () => null,
  open_info: () => ({ version: "0.18.0-next.2", updated: false, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "valid" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  open_launcher: () => null,
};

const app = await bootWindow({ replies });
const { window, el, waitFor, tick } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);

test("an older status reply that lands after a newer one is ignored", async () => {
  await waitFor(() => releaseSlow != null, { label: "the slow boot refresh" });
  window.dispatchEvent(new window.Event("blur"));
  window.dispatchEvent(new window.Event("focus"));
  await waitFor(() => $('#main [data-act="play"]') != null, { label: "the ready home (rev 2)", timeout: 3000 });

  releaseSlow();
  await tick(100);
  assert.ok($('#main [data-act="play"]'), "still the ready home: the rev 1 reply did not overwrite rev 2");
  assert.doesNotMatch($("#main .heros .hero").textContent, /Choose Your Setup/);
});
