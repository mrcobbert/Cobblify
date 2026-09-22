import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The real launcher window under JSDOM: `index.html` plus the shipping
 * `main.js`, driven through its own controls with a scripted Tauri bridge.
 *
 * `main.js` holds its whole state at module scope, so the window boots ONCE
 * per file and the tests below run in order, each leaving the app on a
 * launchable home for the next one.
 */

const PREFS = { autoJoinHypixel: true, useExternalOverlay: true, health: "valid" };
const READY_SETUP = {
  state: "ready",
  mod_version: "0.15.1",
  targets: [{ kind: "lunar", state: "ready", label: "Lunar Client" }],
};
const UPDATE_CURRENT = { state: "current", currentVersion: "0.15.1" };

const PREEXISTING = { status: "preexisting_game", preferences: PREFS };
const REJECTED = { status: "rejected", code: "launch_cooldown", message: "wait" };
const LAUNCHED = {
  status: "launched",
  outcome: {
    autoJoinHypixel: true,
    useExternalOverlay: true,
    action: "game_launch_requested",
  },
  generation: 1,
};

const player = {
  name: "you_",
  state: "OK",
  nicked: false,
  realName: null,
  rank: "",
  rankCodes: "",
  mode: "Overall",
  fkdr: 1,
  wlr: 1,
  finalKills: 0,
  kd: 1,
  fkdrTier: 0,
  cheater: false,
  badge: null,
  chips: [],
  seraphThreat: -1,
};

/** A valid v2 live snapshot, the shape `lobby-poll-handler.test.js` pins. */
function liveSnap(extra = {}) {
  return {
    v: 2,
    seq: 1,
    jvmPid: 42,
    jvmStartTimeMs: 1_700_000_000_000,
    context: "LOBBY",
    inHypixel: true,
    self: "you_",
    mode: null,
    partyCount: null,
    yourParty: [player],
    players: [],
    teams: [],
    ...extra,
  };
}

const replies = {
  refresh_setup: () => READY_SETUP,
  launch_preferences: () => PREFS,
  update_preferences: () => ({
    autoUpdateEnabled: false,
    autoUpdatePrompted: true,
    updateChannel: "stable",
    health: "valid",
  }),
  update_status: () => UPDATE_CURRENT,
  check_for_update: () => UPDATE_CURRENT,
  launch_lunar: () => REJECTED,
  lobby_state: () => ({ kind: "unavailable" }),
  acknowledge_lobby_snapshot: () => ({}),
  launch_progress: () => ({ stage: "fired" }),
  abort_launch_session: () => ({ status: "ok", preferences: PREFS }),
  reset_session_end: () => ({ status: "ok", preferences: PREFS }),
  set_update_channel: () => ({
    status: "saved",
    autoUpdateEnabled: false,
    autoUpdatePrompted: true,
    updateChannel: "dev",
  }),
};

const app = await bootWindow({ replies });
const { window, el, waitFor, tick, countCalls } = app;
const stage = el("stage");
const launch = el("launch");
const connAction = el("conn-action");
const prefError = el("pref-error");
const dash = el("dash");
const devChannel = el("dev-channel");

await waitFor(() => stage.dataset.state === "ready", { label: "the ready home view" });

/** Park the app on the preexisting-game terminal, where Try Again lives. */
async function enterPreexistingGame() {
  replies.launch_lunar = () => PREEXISTING;
  launch.click();
  await waitFor(() => connAction.hidden === false, { label: "the Try Again button" });
  assert.equal(stage.dataset.view, "dash");
}

test("Try Again into a rejected launch lands back on the home view with the reason (J1)", async () => {
  await enterPreexistingGame();
  replies.launch_lunar = () => REJECTED;

  connAction.click();
  await waitFor(() => prefError.hidden === false, { label: "the rejection message" });

  assert.equal(stage.dataset.view, "home");
  assert.equal(dash.classList.contains("on"), false);
  assert.equal(el("joining").classList.contains("on"), false);
  assert.equal(prefError.textContent, "wait");
  assert.equal(launch.disabled, false);
});

test("Try Again into a launch that throws lands back on the home view too (J1)", async () => {
  await enterPreexistingGame();
  replies.launch_lunar = () => {
    throw "the launcher did not answer";
  };

  connAction.click();
  await waitFor(() => prefError.textContent === "the launcher did not answer", {
    label: "the transport error",
  });

  assert.equal(stage.dataset.view, "home");
  assert.equal(prefError.hidden, false);
  assert.equal(launch.disabled, false);
});

test("Try Again into a successful launch starts on home with Cancel reachable (J1)", async () => {
  await enterPreexistingGame();
  replies.launch_lunar = () => LAUNCHED;

  connAction.click();
  await waitFor(() => el("cancel-launch").hidden === false, { label: "the Cancel control" });
  assert.equal(stage.dataset.view, "home");

  // Back to a launchable home for the tests that follow.
  el("cancel-launch").click();
  await waitFor(
    () => stage.dataset.view === "home" && el("cancel-launch").hidden === true,
    { label: "the cancelled launch to reset home" },
  );
});

test("a rejected launch re-arms the background refresh (J5)", async () => {
  replies.launch_lunar = () => REJECTED;
  const before = countCalls("refresh_setup");

  launch.click();
  await waitFor(() => prefError.hidden === false, { label: "the rejection message" });
  assert.equal(launch.classList.contains("is-loading"), false);

  window.dispatchEvent(new window.Event("blur"));
  window.dispatchEvent(new window.Event("focus"));
  await tick(500);

  assert.ok(countCalls("refresh_setup") > before, "focus refreshed the setup again");
});

test("a launch that throws re-arms the background refresh (J5)", async () => {
  replies.launch_lunar = () => {
    throw "the launcher did not answer";
  };
  const before = countCalls("refresh_setup");

  launch.click();
  await waitFor(() => prefError.textContent === "the launcher did not answer", {
    label: "the transport error",
  });

  window.dispatchEvent(new window.Event("blur"));
  window.dispatchEvent(new window.Event("focus"));
  await tick(500);

  assert.ok(countCalls("refresh_setup") > before, "focus refreshed the setup again");
});

test("the dev-channel box keeps the user's tick while the save is in flight (J6)", async () => {
  let landSave;
  replies.set_update_channel = () => new Promise((resolve) => (landSave = resolve));

  devChannel.checked = true;
  devChannel.dispatchEvent(new window.Event("change"));
  await waitFor(() => devChannel.disabled === true, { label: "the disabled box" });
  assert.equal(devChannel.checked, true, "the tick survives the save");

  landSave({
    status: "saved",
    autoUpdateEnabled: false,
    autoUpdatePrompted: true,
    updateChannel: "dev",
  });
  await waitFor(() => devChannel.disabled === false, { label: "the saved channel" });
  assert.equal(devChannel.checked, true);

  // And the box still answers to the value the backend actually saved.
  replies.set_update_channel = () => ({
    status: "saved",
    autoUpdateEnabled: false,
    autoUpdatePrompted: true,
    updateChannel: "stable",
  });
  devChannel.checked = false;
  devChannel.dispatchEvent(new window.Event("change"));
  await waitFor(() => devChannel.disabled === false && devChannel.checked === false, {
    label: "the channel back on stable",
  });
});

test("a roster render that throws still shows a visible dashboard with an error note (J7)", async (t) => {
  const errors = [];
  const realError = console.error;
  console.error = (...args) => errors.push(args);
  Object.defineProperty(dash, "innerHTML", {
    get: () => "",
    set() {
      throw new Error("boom");
    },
    configurable: true,
  });
  t.after(() => {
    console.error = realError;
    delete dash.innerHTML;
  });

  replies.launch_lunar = () => LAUNCHED;
  replies.lobby_state = () => ({ kind: "snapshot", snapshot: liveSnap(), token: 1 });
  launch.click();

  await waitFor(() => stage.dataset.view === "dash", { label: "the dashboard view" });
  await waitFor(() => dash.classList.contains("on"), {
    label: "the dashboard to stay visible after a failed render",
  });
  const note = dash.querySelector(".dash-error");
  assert.ok(note, "the failed render leaves a note in the dashboard");
  assert.match(note.textContent, /boom/);
  assert.ok(
    errors.some((args) => args.some((a) => a instanceof Error && a.message === "boom")),
    "the exception reached console.error",
  );

  // The next snapshot recovers: the roster draws and the note is gone.
  delete dash.innerHTML;
  replies.lobby_state = () => ({ kind: "snapshot", snapshot: liveSnap({ seq: 2 }), token: 2 });
  await waitFor(() => dash.querySelector(".sheet") != null, { label: "the recovered roster" });
  assert.equal(dash.querySelector(".dash-error"), null);
  assert.equal(dash.classList.contains("on"), true);
});

// Code review round 1 coverage gap (A-J2): the real window, not a fake controller. The
// first live snapshot's acknowledge is still in flight when the user cancels the launch;
// the reset commits home, then the ack resolves. The dashboard must stay closed.
test("an acknowledge that resolves after Cancel Launch committed home does not reopen the dashboard (J2)", async () => {
  // Leave the dashboard the J7 test ended on: the backend reports the session ended.
  replies.lobby_state = () => ({ kind: "session_ended", reason: "game_session_ended" });
  replies.reset_session_end = () => ({ status: "ok", preferences: PREFS });
  await waitFor(() => stage.dataset.view === "home" && launch.disabled === false, {
    label: "home after the previous session ended",
  });

  let resolveAck;
  const ackSeen = new Promise((resolveSeen) => {
    replies.acknowledge_lobby_snapshot = () =>
      new Promise((resolve) => {
        resolveAck = resolve;
        resolveSeen();
      });
  });
  replies.launch_lunar = () => LAUNCHED;
  replies.lobby_state = () => ({ kind: "snapshot", snapshot: liveSnap({ seq: 3 }), token: 3 });
  replies.abort_launch_session = () => ({ status: "ok", preferences: PREFS });
  launch.click();
  await ackSeen;
  assert.equal(stage.dataset.view, "home", "the home view is held until the ack lands");

  // Cancel while the ack is pending: the reset invalidates the session and commits home.
  replies.lobby_state = () => ({ kind: "unavailable", reason: "missing_file" });
  el("cancel-launch").click();
  await waitFor(() => launch.disabled === false && launch.hidden === false, {
    label: "a launchable home after Cancel",
  });
  assert.equal(stage.dataset.view, "home");

  // Now the stale ack resolves.
  resolveAck({});
  await tick(50);
  assert.equal(stage.dataset.view, "home", "a stale ack must not flip the view to the dashboard");
  assert.equal(dash.classList.contains("on"), false);
  assert.equal(dash.querySelector(".sheet"), null);
});
