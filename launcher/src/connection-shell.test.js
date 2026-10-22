import test from "node:test";
import assert from "node:assert/strict";

import { createConnectionShell } from "./connection-shell.js";

function fakeEl() {
  return {
    textContent: "",
    hidden: true,
    classList: { on: false, toggle(_c, v) { this.on = v; } },
    dataset: {},
    setAttribute(_k, _v) {},
    listeners: [],
    addEventListener(_type, fn) {
      this.listeners.push(fn);
    },
    click() {
      for (const fn of this.listeners) fn();
    },
  };
}

function makeShell(overrides = {}) {
  const titleEl = fakeEl();
  const subEl = fakeEl();
  const shellEl = fakeEl();
  const actionEl = fakeEl();
  const announceEl = fakeEl();
  let dashView = null;
  let rosterCleared = 0;
  let progressStopped = 0;
  const fired = [];
  const shell = createConnectionShell({
    titleEl,
    subEl,
    shellEl,
    actionEl,
    actions: {
      preexisting_game: {
        label: "Try Again",
        onAction: () => {
          fired.push("try_again");
          overrides.onTryAgain?.();
        },
      },
    },
    announceEl,
    clearRoster: () => {
      rosterCleared += 1;
    },
    stopProgress: () => {
      progressStopped += 1;
    },
    showDash: () => {
      dashView = "dash";
    },
    hideDash: () => {
      dashView = null;
    },
  });
  return {
    shell,
    titleEl,
    subEl,
    shellEl,
    actionEl,
    announceEl,
    fired,
    get dashView() {
      return dashView;
    },
    get rosterCleared() {
      return rosterCleared;
    },
    get progressStopped() {
      return progressStopped;
    },
  };
}

test("connected announcement precedes hiding the shell", () => {
  const ctx = makeShell();
  ctx.shell.show("manual");
  assert.equal(ctx.shell.getVisible(), true);
  ctx.shell.enterConnected({ firstLive: true });
  assert.equal(ctx.announceEl.textContent, "Connected to Hypixel");
  assert.equal(ctx.shell.getVisible(), false);
  assert.equal(ctx.dashView, "dash");
  assert.equal(ctx.progressStopped, 1);
});

test("reconnect announcement is one-shot", () => {
  const ctx = makeShell();
  ctx.shell.enterConnected({ firstLive: true });
  ctx.shell.show("disconnected");
  ctx.shell.enterConnected({ reconnect: true });
  assert.equal(ctx.announceEl.textContent, "Reconnected to Hypixel");
});

test("ordinary consecutive live polls do not reconnect announce", () => {
  const ctx = makeShell();
  ctx.shell.enterConnected({ firstLive: true });
  ctx.announceEl.textContent = "";
  ctx.shell.enterConnected({ reconnect: false });
  assert.equal(ctx.announceEl.textContent, "");
  ctx.shell.enterConnected({ reconnect: true });
  assert.equal(ctx.announceEl.textContent, "Reconnected to Hypixel");
});

test("preexisting_game shows try again and clears roster", () => {
  const ctx = makeShell();
  ctx.shell.enterTerminal("preexisting_game", { showTryAgain: true });
  assert.equal(ctx.titleEl.textContent, "Game Already Running");
  assert.equal(ctx.actionEl.hidden, false);
  assert.equal(ctx.actionEl.textContent, "Try Again");
  assert.equal(ctx.rosterCleared, 1);
  assert.equal(ctx.progressStopped, 1);
});

test("the action slot is per-mode: try again or nothing", () => {
  const ctx = makeShell();
  const seen = [];
  for (const mode of [
    "manual",
    "disconnected",
    "preexisting_game",
    "session_ended",
    "session_changed",
    "launch_aborted",
    "launch_aborted_resetting",
  ]) {
    ctx.shell.show(mode);
    seen.push([mode, ctx.actionEl.hidden ? null : ctx.actionEl.textContent]);
  }
  assert.deepEqual(seen, [
    ["manual", null],
    ["disconnected", null],
    ["preexisting_game", "Try Again"],
    ["session_ended", null],
    ["session_changed", null],
    ["launch_aborted", null],
    ["launch_aborted_resetting", null],
  ]);
});

test("the action slot dispatches to the action of the mode on screen", () => {
  const ctx = makeShell();
  ctx.shell.show("manual");
  ctx.actionEl.click();
  ctx.shell.enterTerminal("preexisting_game", { showTryAgain: true });
  ctx.actionEl.click();
  // A mode with no action swallows the click instead of firing a stale one.
  ctx.shell.enterTerminal("session_ended");
  ctx.actionEl.click();
  assert.deepEqual(ctx.fired, ["try_again"]);
});

test("terminal reset modes never offer an escape button", () => {
  const ctx = makeShell();
  for (const mode of ["session_ended", "session_changed", "launch_aborted_resetting"]) {
    ctx.shell.enterTerminal(mode, { showTryAgain: true });
    assert.equal(ctx.actionEl.hidden, true, mode);
  }
  // preexisting_game without the flag stays button-less too.
  ctx.shell.enterTerminal("preexisting_game");
  assert.equal(ctx.actionEl.hidden, true);
});

test("terminal session modes use approved copy", () => {
  const ctx = makeShell();
  ctx.shell.enterTerminal("session_ended");
  assert.equal(ctx.titleEl.textContent, "Game Session Ended");
  assert.match(ctx.subEl.textContent, /Quit Cobblify/);
  ctx.shell.enterTerminal("session_changed");
  assert.equal(ctx.titleEl.textContent, "Game Session Changed");
});

test("no joining or waiting screen exists: an unknown mode falls back to manual", () => {
  const ctx = makeShell();
  ctx.shell.show("joining");
  assert.equal(ctx.titleEl.textContent, "Ready");
  assert.equal(ctx.shell.scheduleWaitingDeadline, undefined);
});

test("disconnected clears roster before showing shell", () => {
  const ctx = makeShell();
  ctx.shell.enterDisconnected();
  assert.equal(ctx.rosterCleared, 1);
  assert.equal(ctx.titleEl.textContent, "Join Hypixel", "left Hypixel: the Join Hypixel message again");
  assert.equal(ctx.shell.getVisible(), true);
});
