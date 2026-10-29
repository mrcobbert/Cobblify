import test from "node:test";
import assert from "node:assert/strict";
import { bootWindow } from "./test-support/window-harness.js";

const READY = {
  state: "ready",
  mod_version: "0.18.1",
  targets: [
    { kind: "lunar", state: "ready", label: "Lunar Client" },
    { kind: "forge", state: "ready", label: "Prism" },
  ],
};
const app = await bootWindow({ replies: {
  refresh_setup: () => READY,
  open_info: () => ({ version: "0.18.1", updated: true, fresh: false }),
  game_theme: () => ({ accent: "pale-pink", font: "minecraft" }),
  launch_preferences: () => ({ useExternalOverlay: true, health: "valid" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.1" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.1" }),
  lobby_state: () => ({ kind: "unavailable" }),
} });
const { window, document, el, waitFor, tick, calls } = app;
const $ = (selector) => el("app").querySelector(selector);
const key = (key, shiftKey = false) => {
  const event = new window.KeyboardEvent("keydown", { key, shiftKey, bubbles: true, cancelable: true });
  document.activeElement.dispatchEvent(event);
  return event;
};

test("What's New contains keyboard focus, blocks background actions, and releases home on close", async () => {
  await waitFor(() => $(".modal") != null, { label: "What's New" });
  assert.equal($("#main").hasAttribute("inert"), true);
  assert.equal($("#sheet").hasAttribute("inert"), true);
  const buttons = () => [...el("app").querySelectorAll(".modal button")];
  buttons().at(-1).focus();
  assert.equal(key("Tab").defaultPrevented, true);
  assert.equal(document.activeElement, buttons()[0], "Tab wraps inside the modal");
  key("Tab", true);
  assert.equal(document.activeElement, buttons().at(-1), "Shift+Tab wraps inside the modal");

  const launches = calls.filter((c) => c.command.startsWith("launch_")).length;
  $('[data-act="play"]').focus();
  assert.equal($(".modal").contains(document.activeElement), true, "programmatic background focus is contained too");
  $('[data-act="play"]').click();
  $(".caret").click();
  $(".caret").dispatchEvent(new window.KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
  $(".gear").click();
  await tick();
  assert.equal(calls.filter((c) => c.command.startsWith("launch_")).length, launches);
  assert.equal($("#client-menu"), null);
  assert.equal($("#sheet").classList.contains("open"), false);

  buttons().at(-1).focus();
  document.dispatchEvent(new window.CustomEvent("cobblify:units"));
  assert.equal(document.activeElement, buttons().at(-1), "a redraw preserves Continue rather than selecting the first close action");
  key("Escape");
  assert.equal($(".modal"), null);
  assert.equal($("#main").hasAttribute("inert"), false);
  assert.equal($("#sheet").hasAttribute("inert"), false);
  assert.equal(document.activeElement, $('[data-act="play"]'));
});

test("Settings opening focus respects a switch the user already reached", async () => {
  $(".gear").click();
  const auto = $('#sheet [data-key="auto"]');
  auto.focus();
  await tick(180);
  assert.equal(document.activeElement, auto);
  key("Escape");
});

test("Settings cancels opening focus when closed or replaced by release history", async () => {
  $(".gear").click();
  $('#sheet [data-act="close"]').click();
  $('[data-act="play"]').focus();
  await tick(180);
  assert.equal(document.activeElement, $('[data-act="play"]'));

  $(".gear").click();
  $('#sheet [data-act="history"]').click();
  await tick(180);
  assert.equal(document.activeElement, $('.hdr [data-act="back"]'));
  key("Escape");
  $('#sheet [data-act="close"]').click();
});

test("history retains reading position and selection during units, theme, and focus-refresh redraws", async () => {
  $(".gear").click();
  $('#sheet [data-act="history"]').click();
  const rows = [...el("app").querySelectorAll(".hist-list .row")];
  rows.at(-1).click();
  const selected = rows.at(-1).dataset.v;
  $("#reading").scrollTop = 623;
  $(".hist-list").scrollTop = 17;
  for (const event of ["cobblify:units", "cobblify:theme"]) {
    document.dispatchEvent(new window.CustomEvent(event));
    assert.equal($("#reading").scrollTop, 623);
    assert.equal($(".hist-list").scrollTop, 17);
    assert.equal($(".hist-list .row.sel").dataset.v, selected);
  }
  const refreshes = calls.filter((c) => c.command === "refresh_setup").length;
  window.dispatchEvent(new window.Event("blur"));
  window.dispatchEvent(new window.Event("focus"));
  await waitFor(() => calls.filter((c) => c.command === "refresh_setup").length > refreshes, { label: "focus refresh" });
  await tick();
  assert.equal($("#reading").scrollTop, 623);
  assert.equal($(".hist-list .row.sel").dataset.v, selected);

  // An explicit version choice must still navigate instead of restoring the old position.
  $("#reading").scrollTo = ({ top }) => { $("#reading").scrollTop = top; };
  el("app").querySelectorAll(".hist-list .row")[0].click();
  assert.notEqual($(".hist-list .row.sel").dataset.v, selected);
  assert.notEqual($("#reading").scrollTop, 623);
  key("Escape");
  $('#sheet [data-act="close"]').click();
});
