import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * A dev build opened after the one before it, with no note of its own (the user, 2026-11-16: every dev update showed
 * 0.19.0's note about moving to Ornithe). The build replay still plays; What's New stays shut. One boot per file.
 */

const lunar = { kind: "lunar", state: "ready", message: "", action: "none", candidates: [], has_setup_folder: false, installed: true, set_up: true, opened_once: true };
const forge = { kind: "forge", state: "absent", issue: "missing_launcher", message: "", action: "none", candidates: [], has_setup_folder: false, installed: false, set_up: false, instances: [] };
const replies = {
  refresh_setup: () => ({ state: "ready", message: "", mod_version: "0.19.0", targets: [lunar, forge] }),
  setup_state: () => null,
  open_info: () => ({ version: "0.19.0-dev.92", updated: true, fresh: false, previous: "0.19.0-dev.91" }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "valid" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "dev", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.19.0-dev.92" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.19.0-dev.92" }),
  open_launcher: () => null,
};

const app = await bootWindow({ replies });
const { el, waitFor, tick, countCalls } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);

test("dev.92 after dev.91, with no dev.92 note: no What's New, and no 0.19.0 note", async () => {
  await waitFor(() => $('#main [data-act="play"]') != null, { label: "the ready home" });
  await waitFor(() => countCalls("open_info") > 0, { label: "open_info" });
  await tick(2500);
  assert.equal($(".modal"), null);
});
