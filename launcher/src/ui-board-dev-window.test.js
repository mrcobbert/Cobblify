import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The board's header carries the Dev builds tag as home's does. On macOS the window keeps a native title bar, made
 * transparent so the window's ground color shows: macOS places the buttons and hides the bar in full screen, so neither
 * header leaves room for them. One boot per file.
 */

const replies = {
  refresh_setup: () => ({ state: "ready", message: "", mod_version: "0.18.1", rev: 1, targets: [] }),
  setup_state: () => null,
  open_info: () => ({ version: "0.18.1", updated: false, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "valid" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "dev", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.1" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.1" }),
};

const { el, waitFor } = await bootWindow({ replies });

test("the board's header shows the Dev builds tag on the dev channel, as home does", async () => {
  await waitFor(() => !el("dash-dev").hidden, { label: "the board's dev tag" });
  assert.equal(el("dash-dev").textContent, "Dev builds");
  assert.ok(el("app").querySelector(".brand .tag.dev"), "home shows it too");
});

test("macOS keeps a native ground-color title bar and no header makes room for the window buttons", () => {
  const mac = JSON.parse(readFileSync(new URL("../src-tauri/tauri.macos.conf.json", import.meta.url), "utf8")).app.windows[0];
  assert.equal(mac.titleBarStyle, "Transparent");
  assert.equal(mac.backgroundColor, "#1A1A1A");
  assert.equal(mac.trafficLightPosition, undefined, "macOS places the buttons");
  for (const css of ["./ui/app.css", "./dash.css"]) {
    assert.doesNotMatch(readFileSync(new URL(css, import.meta.url), "utf8"), /data-platform/, css);
  }
});
