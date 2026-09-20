import test from "node:test";
import assert from "node:assert/strict";

import { createUpdateController } from "./update-controller.js";

function harness({ status, prefs } = {}) {
  const calls = [];
  const replies = {
    update_preferences: prefs ?? { autoUpdateEnabled: false, autoUpdatePrompted: false, health: "missing" },
    update_status: status ?? { state: "current", currentVersion: "0.9.1" },
    check_for_update: status ?? { state: "current", currentVersion: "0.9.1" },
  };
  const invoke = async (command, args) => {
    calls.push({ command, args });
    if (command === "set_auto_update") {
      return { status: "saved", autoUpdateEnabled: args.enabled, autoUpdatePrompted: true };
    }
    if (command === "set_update_channel") {
      // Native answers with the whole update-preference view, channel included.
      return { status: "saved", autoUpdateEnabled: false, autoUpdatePrompted: true, updateChannel: args.channel };
    }
    return replies[command];
  };
  const timers = [];
  const controller = createUpdateController(invoke, {
    schedule: (fn, ms) => (timers.push({ fn, ms }), timers.length),
    cancelSchedule: () => {},
    random: () => 0.5,
  });
  return { controller, calls, timers };
}

test("bootstrap merges backend status with the separate update preference", async () => {
  const h = harness();
  await h.controller.bootstrap();
  assert.equal(h.controller.getState().autoUpdatePrompted, false);
  assert.deepEqual(h.calls.slice(0, 2).map((c) => c.command), ["update_preferences", "update_status"]);
  assert.equal(h.calls.some((c) => c.command === "check_for_update"), true);
});

test("consent save is explicit and starts a download only after opting in", async () => {
  const h = harness({ status: { state: "available", currentVersion: "0.9.1", availableVersion: "0.10.0" } });
  await h.controller.bootstrap();
  await h.controller.setAutoUpdate(true);
  assert.equal(h.controller.getState().autoUpdateEnabled, true);
  assert.equal(h.controller.getState().autoUpdatePrompted, true);
  assert.equal(h.calls.at(-1).command, "start_update");
});

test("background cadence is six hours with ten-percent jitter", async () => {
  const h = harness();
  await h.controller.bootstrap();
  assert.equal(h.timers.length, 1);
  assert.equal(h.timers[0].ms, 6 * 60 * 60 * 1000);
});

test("manual checks carry manual intent while timer checks do not", async () => {
  const h = harness();
  await h.controller.bootstrap();
  await h.controller.check(true);
  await h.timers.at(-1).fn();
  const checks = h.calls.filter((c) => c.command === "check_for_update");
  assert.deepEqual(checks.map((c) => c.args), [
    { manual: false },
    { manual: true },
    { manual: false },
  ]);
});

test("active gameplay pauses a download and delays automatic work until reset", async () => {
  const h = harness({
    status: { state: "downloading", currentVersion: "0.9.1", downloadedBytes: 10, sizeBytes: 100 },
    prefs: { autoUpdateEnabled: true, autoUpdatePrompted: true, health: "valid" },
  });
  await h.controller.bootstrap();
  await h.controller.setGameActive(true);
  assert.equal(h.calls.at(-1).command, "pause_update");
});

test("auto-update can be enabled and disabled after the first-run choice", async () => {
  const h = harness({ prefs: { autoUpdateEnabled: false, autoUpdatePrompted: true, health: "valid" } });
  await h.controller.bootstrap();
  await h.controller.action("enable");
  await h.controller.action("disable");
  assert.deepEqual(
    h.calls.filter((call) => call.command === "set_auto_update").map((call) => call.args),
    [{ enabled: true }, { enabled: false }],
  );
});

test("the dev-channel box saves the channel, then checks right away", async () => {
  const h = harness({ prefs: { autoUpdateEnabled: false, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" } });
  await h.controller.bootstrap();
  assert.equal(h.controller.getState().updateChannel, "stable");
  const before = h.calls.length;

  await h.controller.setUpdateChannel("dev");
  assert.equal(h.controller.getState().updateChannel, "dev");
  assert.deepEqual(
    h.calls.slice(before).map((call) => [call.command, call.args]),
    [["set_update_channel", { channel: "dev" }], ["check_for_update", { manual: true }]],
  );

  await h.controller.setUpdateChannel("stable");
  assert.equal(h.controller.getState().updateChannel, "stable");
});

test("the channel box is hidden until preferences load, then shown regardless of launch targets", async () => {
  const h = harness({ prefs: { autoUpdateEnabled: false, autoUpdatePrompted: true, updateChannel: "dev", health: "valid" } });
  assert.equal(h.controller.getState().channelLoaded, false);
  await h.controller.bootstrap();
  assert.equal(h.controller.getState().channelLoaded, true);
  assert.equal(h.controller.getState().updateChannel, "dev");
});

test("a second click while a channel save is in flight is dropped, so disk ends on the last accepted choice", async () => {
  const calls = [];
  let release;
  const invoke = async (command, args) => {
    calls.push({ command, args });
    if (command === "set_update_channel") {
      await new Promise((resolve) => { release = resolve; });
      return { status: "saved", autoUpdateEnabled: false, autoUpdatePrompted: true, updateChannel: args.channel };
    }
    if (command === "update_preferences") return { autoUpdateEnabled: false, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" };
    return { state: "current", currentVersion: "0.9.1" };
  };
  const controller = createUpdateController(invoke, { schedule: () => 1, cancelSchedule: () => {}, random: () => 0.5 });
  await controller.bootstrap();
  const first = controller.setUpdateChannel("dev");
  assert.equal(controller.getState().channelSaving, true);
  await controller.setUpdateChannel("stable"); // dropped: nothing sent while saving
  assert.equal(calls.filter((c) => c.command === "set_update_channel").length, 1);
  release();
  await first;
  assert.equal(controller.getState().channelSaving, false);
  assert.equal(controller.getState().updateChannel, "dev");
});

test("a channel the backend could not save surfaces as a preference error and keeps the old channel", async () => {
  const h = harness({ prefs: { autoUpdateEnabled: false, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" } });
  await h.controller.bootstrap();
  const calls = [];
  const invoke = async (command, args) => {
    calls.push(command);
    if (command === "set_update_channel") return { status: "not_saved", diagnostic: "disk full" };
    return { state: "current", currentVersion: "0.9.1" };
  };
  const controller = createUpdateController(invoke, { schedule: () => 1, cancelSchedule: () => {}, random: () => 0.5 });
  await controller.setUpdateChannel("dev");
  assert.equal(controller.getState().state, "error");
  assert.equal(controller.getState().diagnosticCode, "preference_not_saved");
  assert.equal(controller.getState().updateChannel, "stable");
  assert.equal(controller.getState().channelSaving, false);
  assert.deepEqual(calls, ["set_update_channel"]);
});
