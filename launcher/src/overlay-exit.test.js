import test from "node:test";
import assert from "node:assert/strict";

import { createOverlayExit } from "./overlay-exit.js";

function harness({ quitFails = 0 } = {}) {
  const events = [];
  let quitAttempts = 0;
  const exit = createOverlayExit({
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

test("confirmed overlay-off never quits on settled (a quiet log is not loaded)", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("game_launch_requested", false));
  await exit.onConfirmationSignal("settled");
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, []);
});

test("confirmed overlay-off quits at loaded exactly once across duplicates", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("game_launch_requested", false));
  await exit.onConfirmationSignal("loaded");
  await exit.onConfirmationSignal("loaded");
  await exit.onConfirmationSignal("live");
  assert.deepEqual(events, ["quit"]);
});

test("confirmed overlay-off also quits on an acknowledged live snapshot (it is loaded proof)", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("game_launch_requested", false));
  await exit.onConfirmationSignal("live");
  await exit.onConfirmationSignal("loaded");
  assert.deepEqual(events, ["quit"]);
});

test("overlay on never quits, whatever the outcome", async () => {
  for (const action of ["game_launch_requested", "launch_unconfirmed", "launcher_opened"]) {
    const { exit, events } = harness();
    exit.routeFor(outcome(action, true));
    await exit.onConfirmationSignal("settled");
    await exit.onConfirmationSignal("loaded");
    await exit.onConfirmationSignal("live");
    assert.deepEqual(events, [], action);
  }
});

test("unconfirmed overlay-off quits only once Cobblify is known to have loaded, exactly once", async () => {
  const { exit, events } = harness();
  exit.routeFor(outcome("launch_unconfirmed", false));
  await exit.onConfirmationSignal("settled");
  assert.deepEqual(events, [], "settled alone never quits");
  await exit.onConfirmationSignal("live");
  await exit.onConfirmationSignal("loaded");
  assert.deepEqual(events, ["quit"]);
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
  await exit.onConfirmationSignal("loaded");
  assert.deepEqual(events, []);
});

// A quit rejection pending across a session reset and a NEW launch must never
// act on the replacement session (generation scoping).
test("a quit rejection across reset and relaunch never retries into the new session", async () => {
  const events = [];
  let rejectQuit;
  const exit = createOverlayExit({
    quit: () => {
      events.push("quit");
      return new Promise((_, reject) => {
        rejectQuit = reject;
      });
    },
    fallbackToOverlayFlow: () => events.push("fallback"),
  });
  exit.routeFor(outcome("game_launch_requested", false));
  const pending = exit.onConfirmationSignal("loaded");
  assert.deepEqual(events, ["quit"]);
  exit.resetSession();
  exit.routeFor(outcome("game_launch_requested", true));
  rejectQuit(new Error("transport"));
  await pending;
  assert.deepEqual(events, ["quit"], "no retry or fallback spawns into session B");
});
