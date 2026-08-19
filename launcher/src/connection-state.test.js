import test from "node:test";
import assert from "node:assert/strict";

import {
  DISCONNECT_GRACE_MS,
  classifySnapshot,
  createConnectionModel,
} from "./connection-state.js";
import { CONNECTION_COPY, connectionCopy } from "./connection-view.js";

const liveLobby = { context: "LOBBY", inHypixel: true };
const idleMenu = { context: "MENU", inHypixel: false };

test("classifySnapshot recognizes live, non-live, and ignored input", () => {
  assert.equal(classifySnapshot(liveLobby), "live");
  assert.equal(classifySnapshot({ context: "QUEUE", inHypixel: true }), "live");
  assert.equal(classifySnapshot({ context: "GAME", inHypixel: true }), "live");
  assert.equal(classifySnapshot(idleMenu), "non_live");
  assert.equal(classifySnapshot({ context: "LOBBY", inHypixel: false }), "ignore");
  assert.equal(classifySnapshot({ context: "MENU", inHypixel: true }), "ignore");
  assert.equal(classifySnapshot({ context: "MENU" }), "ignore");
  assert.equal(classifySnapshot({ context: "LOBBY" }), "ignore");
  assert.equal(classifySnapshot(null), "ignore");
  assert.equal(classifySnapshot({}), "ignore");
  assert.equal(classifySnapshot({ context: "TRANSFER", inHypixel: false }), "ignore");
  assert.equal(classifySnapshot({ context: "LOBBY", inHypixel: true, seq: 1 }), "live");
});

test("initial session stays joining until the first live snapshot", () => {
  const model = createConnectionModel({ graceMs: 2000 });
  assert.equal(model.tick(idleMenu, 0).mode, "joining");
  assert.equal(model.tick(liveLobby, 100).mode, "connected");
});

test("transient non-live gap after live stays joining during grace", () => {
  const model = createConnectionModel({ graceMs: DISCONNECT_GRACE_MS });
  model.tick(liveLobby, 0);
  assert.equal(model.tick(idleMenu, 500).mode, "joining");
  assert.equal(model.tick(idleMenu, 1500).mode, "joining");
});

test("sustained non-live after live becomes disconnected", () => {
  const model = createConnectionModel({ graceMs: 2000 });
  model.tick(liveLobby, 0);
  model.tick(idleMenu, 1000);
  assert.equal(model.tick(idleMenu, 3000).mode, "disconnected");
  assert.equal(model.tick(idleMenu, 5000).mode, "disconnected");
});

test("fresh live snapshot cancels pending disconnect and reconnects", () => {
  const model = createConnectionModel({ graceMs: 2000 });
  model.tick(liveLobby, 0);
  model.tick(idleMenu, 1000);
  assert.equal(model.tick({ context: "QUEUE", inHypixel: true }, 1500).mode, "connected");
  assert.equal(model.tick(idleMenu, 1600).mode, "joining");
});

test("malformed polls do not start or confirm disconnect alone", () => {
  const model = createConnectionModel({ graceMs: 1000 });
  model.tick(liveLobby, 0);
  assert.equal(model.tick(null, 500).mode, "connected");
  assert.equal(model.tick(undefined, 500).mode, "connected");
  model.tick(idleMenu, 1000);
  assert.equal(model.tick(null, 2000).mode, "joining");
  assert.equal(model.tick(idleMenu, 2000).mode, "disconnected");
});

test("unknown contexts are ignored and do not start disconnect", () => {
  const model = createConnectionModel({ graceMs: 1000 });
  model.tick(liveLobby, 0);
  assert.equal(model.tick({ context: "UNKNOWN", inHypixel: false }, 500).mode, "connected");
  model.tick(idleMenu, 1000);
  assert.equal(model.tick({ context: "UNKNOWN", inHypixel: false }, 2000).mode, "joining");
  assert.equal(model.tick(idleMenu, 2000).mode, "disconnected");
});

test("reset clears prior live history for a new launch attempt", () => {
  const model = createConnectionModel({ graceMs: 1000 });
  model.tick(liveLobby, 0);
  model.tick(idleMenu, 5000);
  assert.equal(model.reset().mode, "joining");
  assert.equal(model.tick(idleMenu, 6000).mode, "joining");
});

test("connection copy maps interstitial modes", () => {
  assert.equal(connectionCopy("joining").title, "Joining Hypixel");
  assert.equal(connectionCopy("disconnected").title, "Disconnected from Hypixel");
  assert.equal(CONNECTION_COPY.disconnected.loading, false);
  assert.match(connectionCopy("disconnected").subtitle, /Reconnect in Minecraft/i);
});
