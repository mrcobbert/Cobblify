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

test("lunar off reads Launch Lunar and settles per outcome", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchPreferences]: () => ({
      autoJoinHypixel: false,
      useExternalOverlay: true,
      health: "valid",
    }),
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 2,
      outcome: {
        autoJoinHypixel: false,
        useExternalOverlay: true,
        action: "launcher_opened",
      },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  // The button reads Launch Lunar in BOTH auto-join states.
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false }, "idle");
  assert.equal(ctrl.getState().launchButtons[0].label, "Launch Lunar");
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false }, "loading");
  assert.equal(ctrl.getState().launchButtons[0].label, "Launching Lunar…");
  // A degraded open-only outcome settles through Lunar Opened.
  await ctrl.launch("lunar", {
    onLaunchReply() {},
    onProgressStart() {},
  });
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

test("auto-join-on launch settles into the in-game joining label", async () => {
  const invoke = fakeInvoke(baseHandlers);
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  const onLabels = ctrl.getState().stageLabels(true);
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: true }, "loading");
  assert.equal(ctrl.getState().launchButtons[0].label, "Heading to Hypixel");
  assert.equal(ctrl.getState().launchButtons[1].label, "Heading to Hypixel");
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: true }, "done");
  const done = ctrl.getState().launchButtons;
  // The settled button reuses the settled stage copy verbatim, so the
  // narration does not flicker to different wording at settle.
  assert.equal(done[0].label, onLabels.settled);
  assert.equal(done[1].label, onLabels.forge_settled);
  assert.equal(done[0].loading, false);
});

// ---- the launched target owns the row --------------------------------------------------------
// With both targets installed, a launch must not make the OTHER button spin or
// narrate: it leaves the row for the whole session and returns on reset.

const BOTH_READY = { lunarReady: true, forgeReady: true };
const launchedReply = (generation) => ({
  status: "launched",
  generation,
  outcome: { autoJoinHypixel: true, useExternalOverlay: true, action: "game_launch_requested" },
});
const noHooks = { onLaunchReply() {}, onProgressStart() {} };

for (const [kind, other] of [
  ["forge", "lunar"],
  ["lunar", "forge"],
]) {
  test(`launching ${kind} projects only the ${kind} button for the whole session`, async () => {
    const cmd = kind === "forge" ? CMD.launchForge : CMD.launchLunar;
    let release;
    const invoke = fakeInvoke({
      ...baseHandlers,
      [cmd]: () => new Promise((resolve) => (release = () => resolve(launchedReply(4)))),
    });
    const ctrl = createLaunchController(invoke);
    await ctrl.refreshPreferences();
    assert.deepEqual(
      ctrl.projectForCurrentPhase(BOTH_READY).map((b) => b.kind),
      ["lunar", "forge"],
    );

    // From the click, before the backend has even answered.
    const pending = ctrl.launch(kind, noHooks);
    assert.equal(ctrl.getState().launchKind, kind);
    let buttons = ctrl.projectForCurrentPhase(BOTH_READY);
    assert.deepEqual(buttons.map((b) => b.kind), [kind]);
    assert.equal(buttons[0].loading, true);
    assert.equal(buttons[0].label, "Heading to Hypixel");

    release();
    await pending;
    buttons = ctrl.projectForCurrentPhase(BOTH_READY);
    assert.deepEqual(buttons.map((b) => b.kind), [kind]);
    assert.equal(buttons[0].loading, true);

    // Settled in game: still only the launched target, no longer spinning.
    ctrl.settleLaunchPhase();
    buttons = ctrl.projectForCurrentPhase(BOTH_READY);
    assert.deepEqual(buttons.map((b) => b.kind), [kind]);
    assert.equal(buttons[0].loading, false);
    assert.equal(ctrl.getState().showAutoJoinSwitch, true);

    // The session ends: the other target comes back, idle.
    ctrl.invalidateSession();
    ctrl.resetLaunchSession();
    assert.equal(ctrl.getState().launchKind, null);
    buttons = ctrl.projectForCurrentPhase(BOTH_READY);
    assert.deepEqual(buttons.map((b) => b.kind), ["lunar", "forge"]);
    assert.ok(buttons.every((b) => !b.loading && b.enabled));
    assert.ok(buttons.find((b) => b.kind === other));
  });
}

test("a launch that never dispatches gives the row back at once", async () => {
  const replies = {
    rejected: { status: "rejected", code: "launch_cooldown", message: "wait" },
    preexisting_game: { status: "preexisting_game" },
  };
  for (const [name, reply] of Object.entries(replies)) {
    const invoke = fakeInvoke({ ...baseHandlers, [CMD.launchForge]: () => reply });
    const ctrl = createLaunchController(invoke);
    await ctrl.refreshPreferences();
    await ctrl.launch("forge", noHooks);
    assert.equal(ctrl.getState().launchKind, null, name);
    assert.deepEqual(
      ctrl.projectForCurrentPhase(BOTH_READY).map((b) => b.kind),
      ["lunar", "forge"],
      name,
    );
  }
  // A thrown invoke is the same story.
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchForge]: () => {
      throw new Error("ipc down");
    },
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("forge", noHooks);
  assert.equal(ctrl.getState().launchKind, null);
  assert.deepEqual(
    ctrl.projectForCurrentPhase(BOTH_READY).map((b) => b.kind),
    ["lunar", "forge"],
  );
});

test("a single installed target is unaffected by row ownership", async () => {
  const invoke = fakeInvoke({ ...baseHandlers, [CMD.launchLunar]: () => launchedReply(5) });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", noHooks);
  const buttons = ctrl.projectForCurrentPhase({ lunarReady: true, forgeReady: false });
  assert.deepEqual(buttons.map((b) => b.kind), ["lunar"]);
  assert.equal(buttons[0].loading, true);
});

test("settleLaunchPhase reaches done for an auto-join-on launch", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 9,
      outcome: {
        autoJoinHypixel: true,
        useExternalOverlay: true,
        action: "game_launch_requested",
      },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  assert.equal(ctrl.getState().launchPhase, "loading");
  ctrl.settleLaunchPhase();
  assert.equal(ctrl.getState().launchPhase, "done");
  const buttons = ctrl.projectForCurrentPhase({ lunarReady: true, forgeReady: false });
  assert.equal(buttons[0].label, "In game - joining Hypixel…");
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

// ── use_external_overlay preference, shared save gate, session reset ───────

const twoKeyHandlers = {
  ...baseHandlers,
  [CMD.launchPreferences]: () => ({
    autoJoinHypixel: true,
    useExternalOverlay: true,
    health: "valid",
  }),
};

test("overlay save resyncs both booleans from the reply", async () => {
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.setUseExternalOverlay]: () => ({
      status: "saved",
      autoJoinHypixel: false,
      useExternalOverlay: false,
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  const st = ctrl.getState();
  assert.equal(st.optimisticOverlay, false);
  assert.equal(st.confirmedOverlay, false);
  // The reply's sibling value wins: full-document resynchronization.
  assert.equal(st.confirmedAutoJoin, false);
});

test("cross-key repair is rejected; same-key repair is admitted", async () => {
  let overlaySaves = 0;
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.setUseExternalOverlay]: () => {
      overlaySaves += 1;
      return overlaySaves === 1
        ? { status: "indeterminate" }
        : { status: "saved", autoJoinHypixel: true, useExternalOverlay: false };
    },
    [CMD.setAutoJoinHypixel]: () => {
      throw new Error("cross-key save must never reach the wire");
    },
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  assert.equal(ctrl.getState().prefUncertain, true);
  assert.equal(ctrl.getState().prefUncertainKey, "overlay");
  // A repair through the OTHER key is refused before any invoke.
  await ctrl.setAutoJoin(true, { repair: true });
  assert.equal(ctrl.getState().prefUncertain, true);
  // The matching key repairs.
  await ctrl.setOverlay(false, { repair: true });
  const st = ctrl.getState();
  assert.equal(st.prefUncertain, false);
  assert.equal(st.confirmedOverlay, false);
});

test("saves are rejected at the controller during launch and active session", async () => {
  let saves = 0;
  let resolveLaunch;
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.setUseExternalOverlay]: () => {
      saves += 1;
      return { status: "saved", autoJoinHypixel: true, useExternalOverlay: false };
    },
    [CMD.setAutoJoinHypixel]: () => {
      saves += 1;
      return { status: "saved", autoJoinHypixel: false, useExternalOverlay: true };
    },
    [CMD.launchLunar]: () =>
      new Promise((resolve) => {
        resolveLaunch = () =>
          resolve({
            status: "launched",
            generation: 3,
            outcome: {
              autoJoinHypixel: true,
              useExternalOverlay: true,
              action: "game_launch_requested",
            },
          });
      }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  const launching = ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  // Mid-launch: a programmatic/stale change event mutates nothing.
  await ctrl.setOverlay(false);
  await ctrl.setAutoJoin(false);
  assert.equal(saves, 0);
  resolveLaunch();
  await launching;
  // Active session: still rejected.
  await ctrl.setOverlay(false);
  assert.equal(saves, 0);
  assert.equal(ctrl.getState().optimisticOverlay, true);
});

test("launch_cooldown surfaces the backend message on the rejection surface", async () => {
  const withMessage = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => ({
      status: "rejected",
      code: "launch_cooldown",
      message: "The previous launch may still be starting - try again in 24s.",
    }),
  });
  const ctrl = createLaunchController(withMessage);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  const st = ctrl.getState();
  assert.equal(st.activeOutcome, null);
  assert.equal(st.launchPhase, "idle");
  assert.match(st.prefError, /try again in 24s/);
});

test("a message-less launch_cooldown falls back to honest copy, not the raw code", async () => {
  const bare = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => ({ status: "rejected", code: "launch_cooldown" }),
  });
  const ctrl = createLaunchController(bare);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  const { prefError } = ctrl.getState();
  assert.equal(
    prefError,
    "The previous launch may still be starting - try again in a moment.",
  );
  assert.doesNotMatch(prefError, /launch_cooldown/);
});

test("stale_preference resynchronizes both booleans", async () => {
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.launchLunar]: () => ({
      status: "rejected",
      code: "stale_preference",
      preferences: { autoJoinHypixel: false, useExternalOverlay: false, health: "valid" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  const st = ctrl.getState();
  assert.equal(st.confirmedAutoJoin, false);
  assert.equal(st.confirmedOverlay, false);
});

test("launch_unconfirmed starts progress and keeps loading phase", async () => {
  let progressStarted = 0;
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 4,
      outcome: {
        autoJoinHypixel: false,
        useExternalOverlay: true,
        action: "launch_unconfirmed",
      },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", {
    onLaunchReply() {},
    onProgressStart() {
      progressStarted += 1;
    },
  });
  assert.equal(progressStarted, 1, "unconfirmed installs progress watching");
  assert.equal(ctrl.getState().launchPhase, "loading");
});

test("invalidateSession orphans stale callbacks and reset restores idle home", async () => {
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 5,
      outcome: {
        autoJoinHypixel: true,
        useExternalOverlay: true,
        action: "game_launch_requested",
      },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  assert.equal(ctrl.getState().launchGen, 5);
  ctrl.invalidateSession();
  const st = ctrl.getState();
  assert.equal(st.launchGen, 0, "no backend generation can match again");
  assert.equal(st.pollGen, 0);
  ctrl.resetLaunchSession({
    autoJoinHypixel: false,
    useExternalOverlay: false,
    health: "valid",
  });
  const after = ctrl.getState();
  assert.equal(after.activeOutcome, null);
  assert.equal(after.launchPhase, "idle");
  assert.equal(after.confirmedAutoJoin, false);
  assert.equal(after.confirmedOverlay, false);
});

test("a stale lobby poll callback after invalidateSession is dropped", async () => {
  let resolvePoll;
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 6,
      outcome: {
        autoJoinHypixel: true,
        useExternalOverlay: true,
        action: "game_launch_requested",
      },
    }),
    [CMD.lobbyState]: () =>
      new Promise((resolve) => {
        resolvePoll = () => resolve({ kind: "session_ended", reason: "game_session_ended" });
      }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  ctrl.bumpPollGen();
  let delivered = 0;
  const pending = ctrl.pollLobbyOnce({
    onPoll: () => {
      delivered += 1;
    },
  });
  // The reset invalidates while the poll response is in flight.
  ctrl.invalidateSession();
  resolvePoll();
  await pending;
  assert.equal(delivered, 0, "stale callback mutates nothing after invalidation");
});

test("a second launch after reset uses the fresh backend generation", async () => {
  let generation = 5;
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation,
      outcome: {
        autoJoinHypixel: true,
        useExternalOverlay: true,
        action: "game_launch_requested",
      },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  assert.equal(ctrl.getState().launchGen, 5);
  ctrl.invalidateSession();
  ctrl.resetLaunchSession({ autoJoinHypixel: true, useExternalOverlay: true, health: "valid" });
  generation = 6;
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  const st = ctrl.getState();
  assert.equal(st.launchGen, 6, "fresh generation adopted end to end");
  assert.equal(st.activeOutcome?.action, "game_launch_requested");
});
