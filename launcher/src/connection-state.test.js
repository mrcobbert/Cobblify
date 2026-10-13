import test from "node:test";
import assert from "node:assert/strict";

import {
  DISCONNECT_GRACE_MS,
  createConnectionModel,
} from "./connection-state.js";

const liveLobby = { context: "LOBBY", inHypixel: true };
const idleMenu = { context: "MENU", inHypixel: false };

test("manual before live: the player joins Hypixel themselves", () => {
  const model = createConnectionModel({ graceMs: 2000 });
  model.beginLaunchSession({ now: 0 });
  assert.equal(model.tick("non_live", 100).mode, "manual");
  assert.equal(model.tick("live", 200).mode, "connected");
});

test("no joining or waiting mode exists, however long the wait", () => {
  const model = createConnectionModel();
  model.beginLaunchSession({ now: 0 });
  assert.equal(model.stateAt(0).mode, "manual");
  assert.equal(model.stateAt(10 * 60_000).mode, "manual");
  assert.equal(model.reset().mode, "manual");
});

test("disconnect grace requires two acknowledged menus", () => {
  const model = createConnectionModel({ graceMs: DISCONNECT_GRACE_MS });
  model.beginLaunchSession({ now: 0 });
  model.tick("live", 0);
  model.tick("non_live", 250);
  // Mid-grace with a single ack: still connected.
  assert.equal(model.stateAt(750).mode, "connected");
  assert.equal(model.tick("ignore", 750).mode, "connected");
  // Second ack, taken once the full grace has elapsed.
  assert.equal(model.tick("non_live", 1250).mode, "disconnected");
});

test("unavailable poll does not confirm disconnect during grace", () => {
  const model = createConnectionModel({ graceMs: DISCONNECT_GRACE_MS });
  model.beginLaunchSession({ now: 0 });
  model.tick("live", 0);
  model.tick("non_live", 250);
  // An ignored poll never confirms a disconnect, not even past the grace.
  assert.equal(model.tick("ignore", 1500).mode, "connected");
});

test("contradictory snapshot is ignored", () => {
  const model = createConnectionModel({ graceMs: 2000 });
  model.beginLaunchSession({ now: 0 });
  model.tick("live", 0);
  // LOBBY/false and MENU/true are "other" — must not start disconnect.
  assert.equal(model.tick("ignore", 500).mode, "connected");
});

test("session ended terminal mode", () => {
  const model = createConnectionModel();
  const ended = model.sessionEnded("game_session_ended");
  assert.equal(ended.mode, "session_ended");
  assert.equal(model.stateAt(0).mode, "session_ended");
});
