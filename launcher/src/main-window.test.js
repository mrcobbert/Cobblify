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

const PREFS = { useExternalOverlay: true, health: "valid" };
const READY_SETUP = {
  state: "ready",
  mod_version: "0.15.1",
  targets: [{ kind: "lunar", state: "ready", label: "Lunar Client" }],
};
const UPDATE_CURRENT = { state: "current", currentVersion: "0.15.1" };
const OPEN_INFO = { version: "0.18.0-next.1", updated: false, fresh: true };

const PREEXISTING = { status: "preexisting_game", preferences: PREFS };
const REJECTED = { status: "rejected", code: "launch_cooldown", message: "wait" };
const LAUNCHED = {
  status: "launched",
  outcome: {
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
  open_info: () => OPEN_INFO,
  game_theme: () => ({}),
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
const { window, document, el, waitFor, tick, countCalls } = app;
const stage = el("stage");
const home = el("app");
const connAction = el("conn-action");
const dash = el("dash");

// The 0.18 home re-renders its controls, so each lookup is fresh.
const playKey = () => home.querySelector('#main [data-act="play"]');
const heroKey = () => home.querySelector("#main .heros .hero");
const cancelKey = () => home.querySelector('#main .keys2 [data-act="cancel"]');
const said = () => home.querySelector("#main .sr")?.textContent ?? "";
const launchable = () => {
  const k = playKey();
  return !!k && k.getAttribute("aria-disabled") !== "true" && !home.hidden;
};

await waitFor(() => launchable(), { label: "the ready home view" });

/** Park the app on the preexisting-game terminal, where Try Again lives. */
async function enterPreexistingGame() {
  replies.launch_lunar = () => PREEXISTING;
  playKey().click();
  await waitFor(() => connAction.hidden === false, { label: "the Try Again button" });
  assert.equal(stage.dataset.view, "dash");
  assert.equal(home.hidden, true, "the home window steps aside for the dashboard view");
}

test("Try Again into a rejected launch lands back on the home view with the reason (J1)", async () => {
  await enterPreexistingGame();
  replies.launch_lunar = () => REJECTED;

  connAction.click();
  await waitFor(() => said() === "wait", { label: "the rejection message" });

  assert.equal(stage.dataset.view, "home");
  assert.equal(home.hidden, false);
  assert.equal(dash.classList.contains("on"), false);
  assert.equal(el("joining").classList.contains("on"), false);
  assert.equal(heroKey().textContent.trim(), "Try again");
  assert.ok(launchable(), "Try again launches again");
});

test("Try Again into a launch that throws lands back on the home view too (J1)", async () => {
  await enterPreexistingGame();
  replies.launch_lunar = () => {
    throw "the launcher did not answer";
  };

  connAction.click();
  await waitFor(() => said() === "the launcher did not answer", {
    label: "the transport error",
  });

  assert.equal(stage.dataset.view, "home");
  assert.ok(launchable());
});

test("Try Again into a successful launch starts on home with Cancel reachable (J1)", async () => {
  await enterPreexistingGame();
  replies.launch_lunar = () => LAUNCHED;

  connAction.click();
  await waitFor(() => cancelKey() != null, { label: "the Cancel control" });
  assert.equal(stage.dataset.view, "home");
  assert.ok(heroKey().classList.contains("prog"), "the Launch key is the progress");

  // Back to a launchable home for the tests that follow.
  cancelKey().click();
  await waitFor(() => stage.dataset.view === "home" && cancelKey() == null && launchable(), {
    label: "the cancelled launch to reset home",
  });
});

test("a rejected launch re-arms the background refresh (J5)", async () => {
  replies.launch_lunar = () => REJECTED;
  const before = countCalls("refresh_setup");

  playKey().click();
  await waitFor(() => said() === "wait", { label: "the rejection message" });
  assert.equal(heroKey().classList.contains("prog"), false);

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

  playKey().click();
  await waitFor(() => said() === "the launcher did not answer", {
    label: "the transport error",
  });

  window.dispatchEvent(new window.Event("blur"));
  window.dispatchEvent(new window.Event("focus"));
  await tick(500);

  assert.ok(countCalls("refresh_setup") > before, "focus refreshed the setup again");
});

test("the Dev builds switch keeps the user's click while the save is in flight (J6)", async () => {
  home.querySelector('[data-act="settings"]').click();
  const devSwitch = () => el("sheet").querySelector('.tog[data-key="dev"]');
  await waitFor(() => devSwitch() != null, { label: "the settings sheet" });

  let landSave;
  replies.set_update_channel = () => new Promise((resolve) => (landSave = resolve));

  devSwitch().click();
  await waitFor(() => devSwitch().disabled === true, { label: "the disabled switch" });
  assert.equal(devSwitch().getAttribute("aria-checked"), "true", "the click survives the save");

  landSave({
    status: "saved",
    autoUpdateEnabled: false,
    autoUpdatePrompted: true,
    updateChannel: "dev",
  });
  await waitFor(() => devSwitch().disabled === false, { label: "the saved channel" });
  assert.equal(devSwitch().getAttribute("aria-checked"), "true");

  // And the switch still answers to the value the backend actually saved.
  replies.set_update_channel = () => ({
    status: "saved",
    autoUpdateEnabled: false,
    autoUpdatePrompted: true,
    updateChannel: "stable",
  });
  devSwitch().click();
  await waitFor(() => devSwitch().disabled === false && devSwitch().getAttribute("aria-checked") === "false", {
    label: "the channel back on stable",
  });
  el("sheet").querySelector('[data-act="close"]').click();
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
  playKey().click();

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
  await waitFor(() => dash.querySelector(".dsheet") != null, { label: "the recovered roster" });
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
  await waitFor(() => stage.dataset.view === "home" && launchable(), {
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
  playKey().click();
  await ackSeen;
  assert.equal(stage.dataset.view, "home", "the home view is held until the ack lands");

  // Cancel while the ack is pending: the reset invalidates the session and commits home.
  replies.lobby_state = () => ({ kind: "unavailable", reason: "missing_file" });
  cancelKey().click();
  await waitFor(() => launchable(), {
    label: "a launchable home after Cancel",
  });
  assert.equal(stage.dataset.view, "home");

  // Now the stale ack resolves.
  resolveAck({});
  await tick(50);
  assert.equal(stage.dataset.view, "home", "a stale ack must not flip the view to the dashboard");
  assert.equal(dash.classList.contains("on"), false);
  assert.equal(dash.querySelector(".dsheet"), null);
});

// TEST-NOTES 2 (the user, 2026-10-11): once Cobblify reports in (`loaded`), home gives way to the dashboard, which
// asks the player to join Hypixel; no Cancel there. On Hypixel the board; back on the title screen the Join Hypixel
// message again; Minecraft quitting returns home.
test("loaded: the dashboard's Join Hypixel message, then the board, the message again off Hypixel, home on quit", async () => {
  replies.acknowledge_lobby_snapshot = () => ({});
  replies.launch_lunar = () => LAUNCHED;
  replies.launch_progress = () => ({ stage: "settled" });
  playKey().click();
  await waitFor(() => /Adding Cobblify/.test(heroKey()?.getAttribute("aria-label") ?? ""), { label: "settled holds the bar", timeout: 3000 });
  assert.equal(stage.dataset.view, "home", "a quiet log is no proof: home and its bar stay");
  assert.ok(cancelKey(), "Cancel while the bar shows");

  replies.launch_progress = () => ({ stage: "settled", loaded: true });
  await waitFor(() => stage.dataset.view === "dash", { label: "the dashboard", timeout: 3000 });
  assert.equal(el("joining-title").textContent, "Join Hypixel");
  assert.equal(el("joining").classList.contains("on"), true);
  assert.equal(home.hidden, true, "home and its Cancel step aside");
  assert.equal(dash.querySelector(".dsheet"), null, "no board before Hypixel");

  replies.lobby_state = () => ({ kind: "snapshot", snapshot: liveSnap({ seq: 10 }), token: 10 });
  await waitFor(() => dash.querySelector(".dsheet") != null, { label: "the board on Hypixel", timeout: 3000 });
  assert.equal(el("joining").classList.contains("on"), false);

  // the title screen after leaving: Minecraft keeps the server's data, so inHypixel + LOBBY with no player
  let seq = 11;
  replies.lobby_state = () => ({ kind: "snapshot", snapshot: liveSnap({ seq: seq++, self: null, yourParty: [] }), token: seq });
  await waitFor(() => el("joining").classList.contains("on") && el("joining-title").textContent === "Join Hypixel", {
    label: "Join Hypixel again",
    timeout: 4000,
  });
  assert.equal(dash.querySelector(".dsheet"), null, "the stale board is gone");

  replies.lobby_state = () => ({ kind: "session_ended", reason: "game_session_ended" });
  await waitFor(() => stage.dataset.view === "home" && launchable(), { label: "home after Minecraft quit", timeout: 3000 });
  replies.lobby_state = () => ({ kind: "unavailable" });
  replies.launch_progress = () => ({ stage: "fired" });
});
