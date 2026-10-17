import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * macOS full screen hides the window buttons the header keeps room for. The window reads Tauri's full-screen state
 * after each resize and marks the app, and app.css drops the room on a marked app. One boot per file.
 */

const replies = {
  refresh_setup: () => ({ state: "ready", message: "", mod_version: "0.18.1", rev: 1, targets: [] }),
  setup_state: () => null,
  open_info: () => ({ version: "0.18.1", updated: false, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "valid" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.1" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.1" }),
};

const app = await bootWindow({
  replies,
  setup: (w) => Object.defineProperty(w.navigator, "platform", { value: "MacIntel", configurable: true }),
});
const { el, emit, waitFor, windowState } = app;
const home = el("app");

test("the app is marked full screen while the window is, read after each resize", async () => {
  assert.equal(home.dataset.platform, "mac");
  assert.equal(home.hasAttribute("data-fullscreen"), false);

  windowState.fullscreen = true;
  emit("tauri://resize", { width: 1512, height: 982 });
  await waitFor(() => home.hasAttribute("data-fullscreen"), { label: "full screen marked" });

  windowState.fullscreen = false;
  emit("tauri://resize", { width: 900, height: 520 });
  await waitFor(() => !home.hasAttribute("data-fullscreen"), { label: "full screen cleared" });
});

test("the header keeps room for the window buttons only outside full screen", () => {
  const css = readFileSync(new URL("./ui/app.css", import.meta.url), "utf8");
  assert.match(css, /\.app\[data-platform=mac\]:not\(\[data-fullscreen\]\) \.hdr \{ padding-left: calc\(40\*var\(--su\)\); \}/);
});
