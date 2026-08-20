import test from "node:test";
import assert from "node:assert/strict";

import {
  CMD,
  launchForge,
  launchLunar,
  setAutoJoinHypixel,
} from "./tauri-contract.js";

test("invoke uses exact wire keys", async () => {
  const calls = [];
  const invoke = (cmd, args) => {
    calls.push({ cmd, args });
    return Promise.resolve({ status: "saved", autoJoinHypixel: false });
  };
  await setAutoJoinHypixel(invoke, false);
  await launchLunar(invoke, true);
  await launchForge(invoke, false);
  assert.equal(calls[0].cmd, CMD.setAutoJoinHypixel);
  assert.deepEqual(calls[0].args, { enabled: false });
  assert.equal(calls[1].cmd, CMD.launchLunar);
  assert.deepEqual(calls[1].args, { expectedAutoJoinHypixel: true });
  assert.equal(calls[2].cmd, CMD.launchForge);
  assert.deepEqual(calls[2].args, { expectedAutoJoinHypixel: false });
});

test("reply tags use snake_case status values", async () => {
  const tags = [
    { status: "not_saved", diagnostic: "disk full" },
    { status: "preexisting_game", preferences: { autoJoinHypixel: true, health: "valid" } },
    { status: "session_ended", reason: "game_session_ended" },
    { status: "indeterminate" },
    { status: "reconciled", autoJoinHypixel: true },
  ];
  for (const reply of tags) {
    assert.match(reply.status, /^[a-z0-9_]+$/);
    assert.doesNotMatch(reply.status, /[A-Z]/);
  }
});
