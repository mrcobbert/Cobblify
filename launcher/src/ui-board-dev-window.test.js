import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The board's header carries the Preview tag as home's does. On macOS the window keeps a native title bar, made
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
  set_update_channel: ({ channel }) => ({ status: "saved", autoUpdateEnabled: true,
    autoUpdatePrompted: true, updateChannel: channel, health: "valid" }),
};

const { el, waitFor, document } = await bootWindow({ replies });

test("the board's header shows the Preview tag on the dev channel, as home does", async () => {
  await waitFor(() => !el("dash-dev").hidden, { label: "the board's dev tag" });
  assert.equal(el("dash-dev").textContent, "Preview");
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


test("Preview updates keeps home and dashboard badges synchronized with the selected channel", async () => {
  el("app").querySelector(".gear").click();
  const toggle = el("sheet").querySelector('[data-key="dev"]');
  assert.equal(toggle.getAttribute("aria-label"), "Preview updates");
  toggle.click();
  await waitFor(() => el("dash-dev").hidden && !toggle.disabled, { label: "stable channel badges" });
  assert.equal(el("app").querySelector(".brand .tag.dev"), null);
  toggle.click();
  await waitFor(() => !el("dash-dev").hidden && !toggle.disabled, { label: "preview channel badges" });
  assert.equal(el("app").querySelector(".brand .tag.dev").textContent, "Preview");
});

test("the top of the window moves it: the title-bar band on home, the band above the board, the settings header", async () => {
  // macOS overlays the native bar with the webview, so only data-tauri-drag-region areas move the window.
  assert.equal(document.body.getAttribute("data-tauri-drag-region"), "", "a press straight on the body, the band above the board");
  const strip = el("app").querySelector(":scope > .tbar");
  assert.ok(strip, "home's band has an invisible strip");
  assert.equal(strip.getAttribute("data-tauri-drag-region"), "", "the strip itself is the drag region");
  const css = readFileSync(new URL("./ui/app.css", import.meta.url), "utf8");
  assert.match(css, /\.tbar\{[^}]*height:var\(--window-top-inset/, "as high as the band #main leaves");
  const z = (rule) => Number(css.match(new RegExp(`${rule}\\{[^}]*z-index:(\\d+)`))[1]);
  assert.ok(z("\\.tbar") > z("\\.col") && z("\\.tbar") > z("\\.home \\.art"), "above the island and Launch's column");
  assert.ok(z("\\.tbar") < z("\\.sheet"), "under the settings sheet, so its close key still clicks");

  el("app").querySelector(".gear").click();
  await waitFor(() => el("sheet").querySelector(".sh-hdr"), { label: "the settings header" });
  const hdr = el("sheet").querySelector(".sh-hdr");
  assert.equal(hdr.getAttribute("data-tauri-drag-region"), "deep", "the sheet's header moves the window too");
  assert.equal(hdr.querySelector(".xkey").getAttribute("data-tauri-drag-region"), null, "its close key stays a button");
});
