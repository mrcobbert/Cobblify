import test from "node:test";
import assert from "node:assert/strict";

import {
  CMD,
  abortLaunchSession,
  launchForge,
  launchLunar,
  quitApp,
  rehideAfterConfirmation,
  resetSessionEnd,
  setAutoJoinHypixel,
  setAutoUpdate,
  setUpdateChannel,
  setUseExternalOverlay,
  checkForUpdate,
  updatePreferences,
  updateStatus,
  startUpdate,
  pauseUpdate,
  resumeUpdate,
  installUpdate,
  deferUpdate,
} from "./tauri-contract.js";

test("invoke uses exact wire keys", async () => {
  const calls = [];
  const invoke = (cmd, args) => {
    calls.push({ cmd, args });
    return Promise.resolve({ status: "saved", autoJoinHypixel: false });
  };
  await setAutoJoinHypixel(invoke, false);
  await setUseExternalOverlay(invoke, false);
  await launchLunar(invoke, true, false);
  await launchForge(invoke, false, true);
  await resetSessionEnd(invoke);
  await rehideAfterConfirmation(invoke, false);
  await quitApp(invoke);
  await abortLaunchSession(invoke);
  assert.equal(calls[0].cmd, CMD.setAutoJoinHypixel);
  assert.deepEqual(calls[0].args, { enabled: false });
  assert.equal(calls[1].cmd, CMD.setUseExternalOverlay);
  assert.deepEqual(calls[1].args, { enabled: false });
  assert.equal(calls[2].cmd, CMD.launchLunar);
  assert.deepEqual(calls[2].args, {
    expectedAutoJoinHypixel: true,
    expectedUseExternalOverlay: false,
  });
  assert.equal(calls[3].cmd, CMD.launchForge);
  assert.deepEqual(calls[3].args, {
    expectedAutoJoinHypixel: false,
    expectedUseExternalOverlay: true,
  });
  assert.equal(calls[4].cmd, CMD.resetSessionEnd);
  assert.equal(calls[4].args, undefined);
  assert.equal(calls[5].cmd, CMD.rehideAfterConfirmation);
  assert.deepEqual(calls[5].args, { useExternalOverlay: false });
  assert.equal(calls[6].cmd, CMD.quitApp);
  assert.equal(calls[6].args, undefined);
  assert.equal(calls[7].cmd, CMD.abortLaunchSession);
  assert.equal(calls[7].args, undefined);
});

test("updater commands keep native names and arguments behind the adapter", async () => {
  const calls = [];
  const invoke = async (cmd, args) => calls.push({ cmd, args });
  await updatePreferences(invoke);
  await updateStatus(invoke);
  await checkForUpdate(invoke, false);
  await setAutoUpdate(invoke, true);
  await setUpdateChannel(invoke, "dev");
  await startUpdate(invoke);
  await pauseUpdate(invoke);
  await resumeUpdate(invoke);
  await installUpdate(invoke);
  await deferUpdate(invoke);
  assert.deepEqual(calls, [
    { cmd: "update_preferences", args: undefined },
    { cmd: "update_status", args: undefined },
    { cmd: "check_for_update", args: { manual: false } },
    { cmd: "set_auto_update", args: { enabled: true } },
    { cmd: "set_update_channel", args: { channel: "dev" } },
    { cmd: "start_update", args: undefined },
    { cmd: "pause_update", args: undefined },
    { cmd: "resume_update", args: undefined },
    { cmd: "install_update", args: undefined },
    { cmd: "defer_update", args: undefined },
  ]);
});

test("abort_launch_session is argument-free and answers in the reset shape", async () => {
  assert.equal(CMD.abortLaunchSession, "abort_launch_session");
  const calls = [];
  const invoke = (cmd, args) => {
    calls.push({ cmd, args });
    return Promise.resolve({
      status: "ok",
      preferences: { autoJoinHypixel: true, useExternalOverlay: true, health: "valid" },
    });
  };
  const reply = await abortLaunchSession(invoke);
  assert.deepEqual(calls, [{ cmd: "abort_launch_session", args: undefined }]);
  // Identical to ResetReply: the orchestrator treats both commands alike.
  assert.equal(reply.status, "ok");
  assert.equal(typeof reply.preferences.autoJoinHypixel, "boolean");
  for (const status of ["ok", "already_reset", "not_terminal", "busy"]) {
    assert.match(status, /^[a-z0-9_]+$/);
  }
});

test("launch_cooldown rides the existing rejected launch shape", () => {
  const reply = {
    status: "rejected",
    code: "launch_cooldown",
    message: "The previous launch may still be starting — try again in 24s.",
  };
  assert.equal(reply.status, "rejected");
  assert.match(reply.code, /^[a-z0-9_]+$/);
  // No new reply variant and no new UI state: code + human message only.
  assert.deepEqual(Object.keys(reply).sort(), ["code", "message", "status"]);
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
