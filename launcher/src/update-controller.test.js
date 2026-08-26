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
