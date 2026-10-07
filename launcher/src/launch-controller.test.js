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
    useExternalOverlay: true,
    health: "valid",
  }),
  [CMD.launchProgress]: () => ({ stage: "fired" }),
  [CMD.lobbyState]: () => ({ kind: "unavailable" }),
};

test("optimistic toggle rolls back on not_saved", async () => {
  const changes = [];
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setUseExternalOverlay]: () => ({ status: "not_saved", diagnostic: "disk full" }),
  });
  const ctrl = createLaunchController(invoke, { onChange: () => changes.push(ctrl.getState()) });
  await ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  const st = ctrl.getState();
  assert.equal(st.optimisticOverlay, true);
  assert.equal(st.confirmedOverlay, true);
  assert.match(st.prefError, /disk full/);
});

test("reconciled reply updates confirmed value", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setUseExternalOverlay]: () => ({ status: "reconciled", useExternalOverlay: false }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  const st = ctrl.getState();
  assert.equal(st.optimisticOverlay, false);
  assert.equal(st.confirmedOverlay, false);
  assert.equal(st.prefUncertain, false);
});

test("indeterminate disables launch and keeps try-again guidance", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setUseExternalOverlay]: () => ({ status: "indeterminate" }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  await ctrl.setOverlay(false);
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
    [CMD.setUseExternalOverlay]: () => {
      calls += 1;
      return calls === 1
        ? { status: "indeterminate" }
        : { status: "saved", useExternalOverlay: true };
    },
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  assert.equal(ctrl.getState().prefUncertain, true);
  await ctrl.setOverlay(true, { repair: true });
  const st = ctrl.getState();
  assert.equal(st.prefUncertain, false);
  assert.equal(st.confirmedOverlay, true);
});

test("stale async preference refresh is discarded", async () => {
  let resolveLate;
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.setUseExternalOverlay]: () => ({ status: "saved", useExternalOverlay: false }),
    [CMD.launchPreferences]: () =>
      new Promise((resolve) => {
        resolveLate = () => resolve({ useExternalOverlay: true, health: "valid" });
      }),
  });
  const ctrl = createLaunchController(invoke);
  const first = ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  resolveLate();
  await first;
  assert.equal(ctrl.getState().optimisticOverlay, false);
});

test("preexisting_game invokes hook without active outcome", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => ({
      status: "preexisting_game",
      preferences: { useExternalOverlay: true, health: "valid" },
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
      outcome: { useExternalOverlay: true, action: "game_launch_requested" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  let progressStarts = 0;
  await ctrl.launch("forge", {
    onLaunchReply: () => {},
    onProgressStart: () => {
      progressStarts += 1;
    },
  });
  const st = ctrl.getState();
  assert.equal(st.launchGen, 7);
  assert.equal(st.activeOutcome?.action, "game_launch_requested");
  assert.equal(progressStarts, 1);
  const labels = st.stageLabels();
  assert.equal(labels.forge_settled, "Prism Started");
  assert.equal(labels.forge_downloading, "Downloading Minecraft");
  assert.doesNotMatch(labels.forge_settled, /Hypixel/i);
});

test("no stage label mentions Hypixel before live: launches never join a server", async () => {
  const invoke = fakeInvoke(baseHandlers);
  const ctrl = createLaunchController(invoke);
  ctrl.projectLaunchButtons({ lunarReady: false, forgeReady: true });
  const labels = ctrl.getState().stageLabels();
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
      useExternalOverlay: true,
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
    [CMD.setUseExternalOverlay]: () =>
      new Promise((resolve) => {
        resolveSave = () => resolve({ status: "not_saved", diagnostic: "disk full" });
      }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: false });
  const save = ctrl.setOverlay(false);
  assert.equal(ctrl.getState().prefSaving, true);
  await ctrl.launch("lunar", {
    onLaunchReply: () => assert.fail("launch must not start during save"),
    onProgressStart: () => {},
  });
  assert.equal(ctrl.getState().prefSaving, true);
  resolveSave();
  await save;
  assert.equal(ctrl.getState().optimisticOverlay, true);
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
        outcome: { useExternalOverlay: true, action: "game_launch_requested" },
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
      useExternalOverlay: true,
      health: "valid",
    }),
    [CMD.launchForge]: () => ({
      status: "launched",
      generation: 3,
      outcome: { useExternalOverlay: true, action: "game_launch_requested" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("forge", { onLaunchReply: () => {}, onProgressStart: () => {} });
  const buttons = ctrl.projectForCurrentPhase({ lunarReady: false, forgeReady: true });
  assert.equal(buttons[0].label, "Launching Prism…");
  assert.equal(buttons[0].loading, true);
});

test("the manual shell copy avoids Hypixel before live", () => {
  assert.doesNotMatch(CONNECTION_COPY.manual.subtitle, /Hypixel|joining|connected/i);
});

test("lunar reads Launch Lunar and settles per outcome", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchPreferences]: () => ({
      useExternalOverlay: true,
      health: "valid",
    }),
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 2,
      outcome: {
        useExternalOverlay: true,
        action: "launcher_opened",
      },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
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
      useExternalOverlay: true,
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

test("a launch never reads as heading to Hypixel, loading or settled", async () => {
  const invoke = fakeInvoke(baseHandlers);
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  const labels = ctrl.getState().stageLabels();
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: true }, "loading");
  assert.deepEqual(
    ctrl.getState().launchButtons.map((b) => b.label),
    ["Launching Lunar…", "Launching Prism…"],
  );
  ctrl.projectLaunchButtons({ lunarReady: true, forgeReady: true }, "done");
  const done = ctrl.getState().launchButtons;
  // The settled button reuses the settled stage copy verbatim, so the
  // narration does not flicker to different wording at settle.
  assert.equal(done[0].label, labels.settled);
  assert.equal(done[1].label, labels.forge_settled);
  assert.equal(done[0].loading, false);
  for (const b of done) assert.doesNotMatch(b.label, /Hypixel/);
});

// ---- the launched target owns the row --------------------------------------------------------
// With both targets installed, a launch must not make the OTHER button spin or
// narrate: it leaves the row for the whole session and returns on reset.

const BOTH_READY = { lunarReady: true, forgeReady: true };
const launchedReply = (generation) => ({
  status: "launched",
  generation,
  outcome: { useExternalOverlay: true, action: "game_launch_requested" },
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
    assert.equal(buttons[0].label, kind === "forge" ? "Launching Prism…" : "Launching Lunar…");

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

test("settleLaunchPhase reaches done for a direct launch", async () => {
  const invoke = fakeInvoke({
    ...baseHandlers,
    [CMD.launchLunar]: () => ({
      status: "launched",
      generation: 9,
      outcome: {
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
  assert.equal(buttons[0].label, "In game…");
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
    [CMD.setUseExternalOverlay]: () =>
      new Promise((resolve) => {
        resolveSave = () => resolve({ status: "saved", useExternalOverlay: false });
      }),
    [CMD.launchLunar]: () => ({ status: "rejected", code: "busy", message: "busy" }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  const save = ctrl.setOverlay(false);
  await ctrl.launch("lunar", {
    onLaunchReply: () => {},
    onProgressStart: () => {},
  });
  resolveSave();
  await save;
  assert.equal(ctrl.getState().confirmedOverlay, false);
  assert.equal(ctrl.getState().prefSaving, false);
});

// ── use_external_overlay preference, save gate, session reset ──────────────

const twoKeyHandlers = {
  ...baseHandlers,
  [CMD.launchPreferences]: () => ({
    useExternalOverlay: true,
    health: "valid",
  }),
};

test("overlay save resyncs from the reply", async () => {
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.setUseExternalOverlay]: () => ({
      status: "saved",
      useExternalOverlay: false,
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  const st = ctrl.getState();
  assert.equal(st.optimisticOverlay, false);
  assert.equal(st.confirmedOverlay, false);
});

test("an uncertain overlay save is repaired only with the same value", async () => {
  let overlaySaves = 0;
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.setUseExternalOverlay]: () => {
      overlaySaves += 1;
      return overlaySaves === 1
        ? { status: "indeterminate" }
        : { status: "saved", useExternalOverlay: false };
    },
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.setOverlay(false);
  assert.equal(ctrl.getState().prefUncertain, true);
  assert.equal(ctrl.getState().prefUncertainKey, "overlay");
  // A plain change to the other value is refused before any invoke.
  await ctrl.setOverlay(true);
  assert.equal(overlaySaves, 1);
  assert.equal(ctrl.getState().prefUncertain, true);
  // The repair goes through.
  await ctrl.setOverlay(false, { repair: true });
  const st = ctrl.getState();
  assert.equal(st.prefUncertain, false);
  assert.equal(st.confirmedOverlay, false);
});

test("the controller has no auto-join surface", () => {
  const ctrl = createLaunchController(fakeInvoke(baseHandlers));
  assert.deepEqual(Object.keys(ctrl).filter((k) => /^set[A-Z]/.test(k)), ["setOverlay"]);
  const st = ctrl.getState();
  for (const key of Object.keys(st)) assert.doesNotMatch(key, /auto.?join/i);
});

test("saves are rejected at the controller during launch and active session", async () => {
  let saves = 0;
  let resolveLaunch;
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.setUseExternalOverlay]: () => {
      saves += 1;
      return { status: "saved", useExternalOverlay: false };
    },
    [CMD.launchLunar]: () =>
      new Promise((resolve) => {
        resolveLaunch = () =>
          resolve({
            status: "launched",
            generation: 3,
            outcome: {
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

test("stale_preference resynchronizes the overlay choice", async () => {
  const invoke = fakeInvoke({
    ...twoKeyHandlers,
    [CMD.launchLunar]: () => ({
      status: "rejected",
      code: "stale_preference",
      preferences: { useExternalOverlay: false, health: "valid" },
    }),
  });
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  const st = ctrl.getState();
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
    useExternalOverlay: false,
    health: "valid",
  });
  const after = ctrl.getState();
  assert.equal(after.activeOutcome, null);
  assert.equal(after.launchPhase, "idle");
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
  ctrl.resetLaunchSession({ useExternalOverlay: true, health: "valid" });
  generation = 6;
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  const st = ctrl.getState();
  assert.equal(st.launchGen, 6, "fresh generation adopted end to end");
  assert.equal(st.activeOutcome?.action, "game_launch_requested");
});
