import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The board's header carries the Dev builds tag as home's does. On macOS the window keeps a native title bar, made
 * transparent while measuring its height, then overlays it so the sheet reaches the top. Main content alone
 * reserves the measured height. macOS places the buttons and removes the inset in full screen. One boot per file.
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

test("macOS lets the sheet reach the window top, reserving the native bar only for main content", () => {
  const mac = JSON.parse(readFileSync(new URL("../src-tauri/tauri.macos.conf.json", import.meta.url), "utf8")).app.windows[0];
  assert.equal(mac.titleBarStyle, "Transparent", "startup measures the native bar before switching to full-size content");
  const native = readFileSync(new URL("../src-tauri/src/main.rs", import.meta.url), "utf8");
  assert.match(native, /set_title_bar_style\(tauri::TitleBarStyle::Overlay\)/);
  assert.equal(mac.backgroundColor, "#1A1A1A");
  assert.equal(mac.trafficLightPosition, undefined, "macOS places the buttons");
  const css = readFileSync(new URL("./ui/app.css", import.meta.url), "utf8");
  assert.match(css, /#main\s*\{[^}]*margin-top:\s*var\(--window-top-inset/);
  assert.match(css, /\.sheet\{[^}]*top:0/);
});
