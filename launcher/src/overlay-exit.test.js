import test from "node:test";
import assert from "node:assert/strict";

import { createOverlayExit } from "./overlay-exit.js";

function harness({ quitFails = 0, rehideFails = 0 } = {}) {
  const events = [];
  let quitAttempts = 0;
  let rehideAttempts = 0;
  const exit = createOverlayExit({
    rehide: async (overlay) => {
      rehideAttempts += 1;
      events.push(`rehide(${overlay})`);
      if (rehideAttempts <= rehideFails) throw new Error("rehide transport");
      events.push("rehide_reply");
    },
    quit: async () => {
      quitAttempts += 1;
      if (quitAttempts <= quitFails) throw new Error("quit transport");
      events.push("quit");
    },
    fallbackToOverlayFlow: () => events.push("fallback"),
  });
  return { exit, events };
}

const outcome = (action, useExternalOverlay) => ({ action, useExternalOverlay });

test("route table is exhaustive over action and overlay", () => {
  const { exit } = harness();
  assert.equal(exit.routeFor(outcome("game_launch_requested", true)), "overlay");
  assert.equal(exit.routeFor(outcome("launcher_opened", true)), "overlay");
  assert.equal(exit.routeFor(outcome("launch_unconfirmed", true)), "overlay");
  assert.equal(exit.routeFor(outcome("launcher_opened", false)), "quit_now");
  assert.equal(exit.routeFor(outcome("game_launch_requested", false)), "no_dashboard");
  assert.equal(exit.routeFor(outcome("launch_unconfirmed", false)), "overlay");
});

test("confirmed overlay-off quits at settled exactly once across duplicates", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("game_launch_requested", false));
  await exit.onConfirmationSignal("settled");
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, ["quit"]);
});

test("confirmed overlay-off ignores the live signal (settled is the trigger)", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("game_launch_requested", false));
  await exit.onConfirmationSignal("live");
  assert.deepEqual(events, []);
});

test("overlay-on confirmed never quits or rehides", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("game_launch_requested", true));
  await exit.onConfirmationSignal("settled");
  await exit.onConfirmationSignal("live");
  assert.deepEqual(events, []);
});

test("unconfirmed: both signals share one rehide; overlay-on never quits", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("launch_unconfirmed", true));
  await Promise.all([
    exit.onConfirmationSignal("live"),
    exit.onConfirmationSignal("settled"),
  ]);
  assert.deepEqual(events, ["rehide(true)", "rehide_reply"]);
});

test("unconfirmed overlay-off exits only after the rehide reply", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("launch_unconfirmed", false));
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, ["rehide(false)", "rehide_reply", "quit"]);
});

test("rehide transport rejection retries once then overlay-off still exits", async () => {
  const { exit, events } = harness({ rehideFails: 2 });
  exit.routeFor(outcome("launch_unconfirmed", false));
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, ["rehide(false)", "rehide(false)", "quit"]);
});

test("rehide double rejection with overlay on stops without quitting", async () => {
  const { exit, events } = harness({ rehideFails: 2 });
  exit.routeFor(outcome("launch_unconfirmed", true));
  await exit.onConfirmationSignal("live");
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, ["rehide(true)", "rehide(true)"]);
});

test("quit rejection retries once then falls back to the overlay flow", async () => {
  const { exit, events } = harness({ quitFails: 2 });
  exit.routeFor(outcome("launcher_opened", false));
  await exit.quitNow();
  assert.deepEqual(events, ["fallback"]);
});

test("quit succeeds on the retry without fallback", async () => {
  const { exit, events } = harness({ quitFails: 1 });
  exit.routeFor(outcome("launcher_opened", false));
  await exit.quitNow();
  assert.deepEqual(events, ["quit"]);
});

test("signals after resetSession are inert (no session, no quit)", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("launch_unconfirmed", false));
  exit.resetSession();
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, []);
});

test("sequential settled-then-live and live-then-settled each rehide once", async () => {
  for (const order of [["settled", "live"], ["live", "settled"]]) {
    const { exit, events } = harness();
    exit.routeFor(outcome("launch_unconfirmed", false));
    await exit.onConfirmationSignal(order[0]);
    await exit.onConfirmationSignal(order[1]);
    assert.deepEqual(events, ["rehide(false)", "rehide_reply", "quit"]);
  }
});

// A rehide pending across a session reset and a NEW launch must never act
// on the replacement session (generation scoping): session A's
// continuation resumes after B began and must not quit B even though B
// has overlay off.
test("a pending rehide across reset and relaunch never quits the new session", async () => {
  const events = [];
  let releaseRehide;
  const exit = createOverlayExit({
    rehide: (overlay) =>
      new Promise((resolve) => {
        events.push(`rehide(${overlay})`);
        releaseRehide = resolve;
      }),
    quit: async () => events.push("quit"),
    fallbackToOverlayFlow: () => events.push("fallback"),
  });
  // Session A: unconfirmed, overlay off; its rehide blocks mid-flight.
  exit.routeFor(outcome("launch_unconfirmed", false));
  const pending = exit.onConfirmationSignal("settled");
  assert.deepEqual(events, ["rehide(false)"]);
  // Home reset commits, then session B (also overlay off) launches.
  exit.resetSession();
  exit.routeFor(outcome("game_launch_requested", false));
  // A's rehide finally resolves: its continuation must be inert.
  releaseRehide();
  await pending;
  assert.deepEqual(events, ["rehide(false)"], "no quit against session B");
  // B's own trigger still works normally afterwards.
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, ["rehide(false)", "quit"]);
});

// The retry continuation is session-guarded too: a rehide REJECTION that
// resolves after reset+relaunch must not retry into the new session.
test("a rehide rejection across reset and relaunch never retries into the new session", async () => {
  const events = [];
  let rejectRehide;
  let calls = 0;
  const exit = createOverlayExit({
    rehide: () => {
      calls += 1;
      events.push(`rehide#${calls}`);
      return new Promise((_, reject) => {
        rejectRehide = reject;
      });
    },
    quit: async () => events.push("quit"),
    fallbackToOverlayFlow: () => events.push("fallback"),
  });
  exit.routeFor(outcome("launch_unconfirmed", true));
  const pending = exit.onConfirmationSignal("settled");
  assert.deepEqual(events, ["rehide#1"]);
  exit.resetSession();
  exit.routeFor(outcome("game_launch_requested", false));
  rejectRehide(new Error("transport"));
  await pending;
  assert.deepEqual(events, ["rehide#1"], "no retry spawns work into session B");
});

test("a pending rehide across a bare reset (no relaunch) never dereferences or quits", async () => {
  const events = [];
  let releaseRehide;
  const exit = createOverlayExit({
    rehide: () =>
      new Promise((resolve) => {
        events.push("rehide");
        releaseRehide = resolve;
      }),
    quit: async () => events.push("quit"),
    fallbackToOverlayFlow: () => events.push("fallback"),
  });
  exit.routeFor(outcome("launch_unconfirmed", false));
  const pending = exit.onConfirmationSignal("live");
  exit.resetSession();
  releaseRehide();
  await pending;
  assert.deepEqual(events, ["rehide"]);
});
