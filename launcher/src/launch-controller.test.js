import test from "node:test";
import assert from "node:assert/strict";

import { createLaunchController } from "./launch-controller.js";
import { CMD } from "./tauri-contract.js";
import { CONNECTION_COPY } from "./connection-view.js";

function fakeInvoke(handlers) {
  return (cmd, args) => {
    const fn = handlers[cmd];
    if (!fn) return Promise.reject(new Error(`unexpected ${cmd}`));
    return Promise.resolve(fn(args));
  };
}

const baseHandlers = {
  [CMD.launchPreferences]: () => ({
    autoJoinHypixel: true,
    health: "valid",
  }),
  [CMD.launchProgress]: () => ({ stage: "fired" }),
  [CMD.lobbyState]: () => ({ kind: "unavailable" }),
};

test("optimistic toggle rolls back on not_saved", async () => {
  const changes = [];
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setAutoJoinHypixel]: () => ({ status: "not_saved", diagnostic: "disk full" }),
  });
  const ctrl = createLaunchController(invoke, { onChange: () => changes.push(ctrl.getState()) });
  await ctrl.refreshPreferences();
  await ctrl.setAutoJoin(false);
  const st = ctrl.getState();
  assert.equal(st.optimisticAutoJoin, true);
  assert.equal(st.confirmedAutoJoin, true);
  assert.match(st.prefError, /disk full/);
});

test("reconciled reply updates confirmed value", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setAutoJoinHypixel]: () => ({ status: "reconciled", autoJoinHypixel: false }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setAutoJoin(false);
  const st = ctrl.getState();
  assert.equal(st.optimisticAutoJoin, false);
  assert.equal(st.confirmedAutoJoin, false);
  assert.equal(st.prefUncertain, false);
});

test("indeterminate disables launch and keeps try-again guidance", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setAutoJoinHypixel]: () => ({ status: "indeterminate" }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  await ctrl.setAutoJoin(false);
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  const st = ctrl.getState();
  assert.equal(st.prefUncertain, true);
  assert.match(st.prefError, /Try again/i);
  assert.equal(st.launchButtons[0].enabled, false);
});

test("repair clears uncertain save path", async () => {
  let calls = 0;
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setAutoJoinHypixel]: () => {
      calls += 1;
      return calls === 1
        ? { status: "indeterminate" }
        : { status: "saved", autoJoinHypixel: true };
    },
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setAutoJoin(false);
  assert.equal(ctrl.getState().prefUncertain, true);
  await ctrl.setAutoJoin(true, { repair: true });
  const st = ctrl.getState();
  assert.equal(st.prefUncertain, false);
  assert.equal(st.confirmedAutoJoin, true);
});

test("stale async preference refresh is discarded", async () => {
  let resolveLate;
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setAutoJoinHypixel]: () => ({ status: "saved", autoJoinHypixel: false }),
    [CMD.launchPreferences]: () =>
      new Promise((resolve) => {
        resolveLate = () => resolve({ autoJoinHypixel: true, health: "valid" });
      }),
  });
  const ctrl = createLaunchController(invoke);
  const first = ctrl.refreshPreferences();
  await ctrl.setAutoJoin(false);
  resolveLate();
  await first;
  assert.equal(ctrl.getState().optimisticAutoJoin, false);
});

test("preexisting_game invokes hook without active outcome", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => ({
      status: "preexisting_game",
      preferences: { autoJoinHypixel: true, health: "valid" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  let seen = null;
  await ctrl.launch("lunar", {
    onLaunchReply: (reply) => {
      seen = reply;
    },
    onProgressStart: () => assert.fail("no progress for preexisting"),
  });
  assert.equal(seen?.status, "preexisting_game");
  assert.equal(ctrl.getState().activeOutcome, null);
});

test("launched records generation and active outcome", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchForge]: () => ({
      status: "launched",
      generation: 7,
      outcome: { autoJoinHypixel: false, action: "game_launch_requested" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setAutoJoin(false, { repair: true });
  let progressAutoJoin;
  await ctrl.launch("forge", {
    onLaunchReply: () => {},
    onProgressStart: (autoJoin) => {
      progressAutoJoin = autoJoin;
    },
  });
  const st = ctrl.getState();
  assert.equal(st.launchGen, 7);
  assert.equal(st.activeOutcome?.action, "game_launch_requested");
  assert.equal(progressAutoJoin, false);
  const labels = st.stageLabels(false);
  assert.equal(labels.forge_settled, "Prism Started");
  assert.doesNotMatch(labels.forge_settled, /Hypixel/i);
});

test("prism-off labels never mention Hypixel before live", async () => {
  const invoke = fakeInvoke(baseHandlers);
  const ctrl = createLaunchController(invoke);
  ctrl.projectLaunchButtons({ lunarReady: false, forgeReady: true });
  const labels = ctrl.getState().stageLabels(false);
  for (const text of Object.values(labels)) {
    assert.doesNotMatch(text, /Hypixel|joining|connected/i);
  }
  const st = ctrl.getState();
  assert.equal(st.launchButtons[0].label, "Launch Prism");
});

test("launch blocked while preference invalid", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchPreferences]: () => ({
      autoJoinHypixel: true,
      health: "invalid",
      diagnostic: "bad json",
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  await ctrl.launch("lunar", {
    onLaunchReply: () => assert.fail("should not launch"),
    onProgressStart: () => {},
  });
  assert.match(ctrl.getState().prefError, /Repair/i);
});

test("launch blocked while preference save is pending", async () => {
  let resolveSave;
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setAutoJoinHypixel]: () =>
      new Promise((resolve) => {
        resolveSave = () => resolve({ status: "not_saved", diagnostic: "disk full" });
      }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  const save = ctrl.setAutoJoin(false);
  assert.equal(ctrl.getState().prefSaving, true);
  await ctrl.launch("lunar", {
    onLaunchReply: () => assert.fail("launch must not start during save"),
    onProgressStart: () => {},
  });
  assert.equal(ctrl.getState().prefSaving, true);
  resolveSave();
  await save;
  assert.equal(ctrl.getState().optimisticAutoJoin, true);
  assert.match(ctrl.getState().prefError, /disk full/);
});

test("active session blocks second launch invoke", async () => {
  let launchCalls = 0;
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => {
      launchCalls += 1;
      return {
        status: "launched",
        generation: 3,
        outcome: { autoJoinHypixel: true, action: "game_launch_requested" },
      };
    },
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  await ctrl.launch("lunar", { onLaunchReply: () => {}, onProgressStart: () => {} });
  assert.equal(launchCalls, 1);
  const st = ctrl.getState();
  assert.equal(st.activeOutcome?.action, "game_launch_requested");
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  assert.equal(ctrl.getState().launchButtons[0].enabled, false);
});

test("projectForCurrentPhase renders loading labels for in-flight launch", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchPreferences]: () => ({
      autoJoinHypixel: false,
      health: "valid",
    }),
    [CMD.launchForge]: () => ({
      status: "launched",
      generation: 3,
      outcome: { autoJoinHypixel: false, action: "game_launch_requested" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("forge", { onLaunchReply: () => {}, onProgressStart: () => {} });
  const buttons = ctrl.projectForCurrentPhase({ lunarReady: false, forgeReady: true });
  assert.equal(buttons[0].label, "Launching Prism…");
  assert.equal(buttons[0].loading, true);
});

test("auto-join-off manual shell copy avoids Hypixel before live", () => {
  assert.doesNotMatch(CONNECTION_COPY.manual.subtitle, /Hypixel|joining|connected/i);
});

test("launch phases show opening and opened labels for lunar off", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchPreferences]: () => ({
      autoJoinHypixel: false,
      health: "valid",
    }),
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 2,
      outcome: { autoJoinHypixel: false, action: "launcher_opened" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false }, "loading");
  assert.equal(ctrl.getState().launchButtons[0].label, "Opening Lunar…");
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false }, "done");
  assert.equal(ctrl.getState().launchButtons[0].label, "Lunar Opened");
});

test("launch phases show prism dispatch labels", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchPreferences]: () => ({
      autoJoinHypixel: false,
      health: "valid",
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: false, forgeReady: true }, "loading");
  assert.equal(ctrl.getState().launchButtons[0].label, "Launching Prism…");
  ctrl.projectLaunchButtons({ lunarReady: false, forgeReady: true }, "done");
  assert.equal(ctrl.getState().launchButtons[0].label, "Prism Started");
});

test("busy launch rejection returns typed reply without active outcome", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => ({ status: "rejected", code: "busy", message: "busy" }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", {
    onLaunchReply: () => {},
    onProgressStart: () => {},
  });
  const st = ctrl.getState();
  assert.equal(st.activeOutcome, null);
  assert.equal(st.launchPhase, "idle");
  assert.match(st.prefError, /busy/i);
});
test("save completion survives rejected launch attempt", async () => {
  let resolveSave;
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setAutoJoinHypixel]: () =>
      new Promise((resolve) => {
        resolveSave = () => resolve({ status: "saved", autoJoinHypixel: false });
      }),
    [CMD.launchLunar]: () => ({ status: "rejected", code: "busy", message: "busy" }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  const save = ctrl.setAutoJoin(false);
  await ctrl.launch("lunar", {
    onLaunchReply: () => {},
    onProgressStart: () => {},
  });
  resolveSave();
  await save;
  assert.equal(ctrl.getState().confirmedAutoJoin, false);
  assert.equal(ctrl.getState().prefSaving, false);
});
