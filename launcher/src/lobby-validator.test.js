import test from "node:test";
import assert from "node:assert/strict";

import { classifyLive, isValidLobbySnapshot } from "./lobby-validator.js";

test("contradictory LOBBY/false is other not non_live", () => {
  const snap = {
    v: 1,
    seq: 1,
    jvmPid: 1,
    jvmStartTimeMs: 1_700_000_000_000,
    context: "LOBBY",
    inHypixel: false,
    self: null,
    mode: null,
    partyCount: null,
    yourParty: [],
    players: [],
    teams: [],
  };
  assert.ok(isValidLobbySnapshot(snap));
  assert.equal(classifyLive(snap), "other");
});

test("MENU/true is other not non_live", () => {
  const snap = {
    v: 1,
    seq: 1,
    jvmPid: 1,
    jvmStartTimeMs: 1_700_000_000_000,
    context: "MENU",
    inHypixel: true,
    self: null,
    mode: null,
    partyCount: null,
    yourParty: [],
    players: [],
    teams: [],
  };
  assert.ok(isValidLobbySnapshot(snap));
  assert.equal(classifyLive(snap), "other");
});

const baseSnap = {
  v: 1,
  seq: 1,
  jvmPid: 1,
  jvmStartTimeMs: 1_700_000_000_000,
  context: "MENU",
  inHypixel: false,
  self: null,
  mode: null,
  partyCount: null,
  yourParty: [],
  players: [],
  teams: [],
};

test("missing required nullable root fields are rejected", () => {
  const { self, mode, partyCount, ...missingRoots } = baseSnap;
  assert.ok(!isValidLobbySnapshot(missingRoots));
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, self: undefined }));
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, mode: undefined }));
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, partyCount: undefined }));
});

test("player missing realName is rejected", () => {
  const player = {
    name: "x",
    state: "OK",
    nicked: false,
    rank: "",
    fkdr: 1,
    wlr: 1,
    kd: 1,
    finalKills: 0,
    seraphThreat: 0,
    seraphTags: [],
    urchinTags: [],
  };
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, players: [player] }));
});

test("seq outside signed int32 is rejected", () => {
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, seq: 2147483648 }));
});

test("partyCount outside signed int32 is rejected", () => {
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, partyCount: -2147483649 }));
});

test("jvmStartTimeMs above safe integer is rejected", () => {
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, jvmStartTimeMs: Number.MAX_SAFE_INTEGER + 1 }));
});

test("jvmStartTimeMs non-integer is rejected", () => {
  assert.ok(!isValidLobbySnapshot({ ...baseSnap, jvmStartTimeMs: 1.5 }));
});
