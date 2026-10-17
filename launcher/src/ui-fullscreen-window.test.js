import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * macOS full screen hides the window buttons the headers keep a strip for. The window reads Tauri's full-screen state
 * after each resize and marks the home and the board, and app.css / dash.css drop the strip on a marked view. The
 * board's header carries the Dev builds tag as home's does. One boot per file.
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

const app = await bootWindow({
  replies,
  setup: (w) => Object.defineProperty(w.navigator, "platform", { value: "MacIntel", configurable: true }),
});
const { el, emit, waitFor, windowState } = app;
const home = el("app");
const stage = el("stage");

test("home and the board are marked full screen while the window is, read after each resize", async () => {
  assert.equal(home.dataset.platform, "mac");
  assert.equal(stage.dataset.platform, "mac");
  assert.equal(home.hasAttribute("data-fullscreen"), false);

  windowState.fullscreen = true;
  emit("tauri://resize", { width: 1512, height: 982 });
  await waitFor(() => home.hasAttribute("data-fullscreen"), { label: "full screen marked" });
  assert.equal(stage.hasAttribute("data-fullscreen"), true);

  windowState.fullscreen = false;
  emit("tauri://resize", { width: 900, height: 520 });
  await waitFor(() => !home.hasAttribute("data-fullscreen"), { label: "full screen cleared" });
  assert.equal(stage.hasAttribute("data-fullscreen"), false);
});

test("the headers keep a strip for the window buttons only outside full screen", () => {
  const app = readFileSync(new URL("./ui/app.css", import.meta.url), "utf8");
  assert.match(app, /\.app\[data-platform=mac\]:not\(\[data-fullscreen\]\) \.hdr \{ height: calc\(36\*var\(--su\)\); padding-top: calc\(12\*var\(--su\)\); \}/);
  const dash = readFileSync(new URL("./dash.css", import.meta.url), "utf8");
  assert.match(dash, /\.stage \{ --hdr-su: 24; \}/);
  assert.match(dash, /\.stage\[data-platform="mac"\]:not\(\[data-fullscreen\]\) \{ --hdr-su: 36; \}/);
});

test("the board's header shows the Dev builds tag on the dev channel", async () => {
  await waitFor(() => !el("dash-dev").hidden, { label: "the board's dev tag" });
  assert.equal(el("dash-dev").textContent, "Dev builds");
  assert.ok(home.querySelector(".brand .tag.dev"), "home shows it too");
});
