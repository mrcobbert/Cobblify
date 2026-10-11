import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * External Overlay off, in the real window (index.html + main.js under JSDOM): the launcher quits
 * itself only once the backend says Cobblify loaded (`launch_progress` `loaded`), never on the
 * cosmetic `settled` stage - a quiet log after mixins is not proof, and quitting there would cut
 * off the didn't-load verdict. One boot per file; the tests run in order.
 */

const PREFS = { useExternalOverlay: false, health: "valid" };
const READY_LUNAR = {
  state: "ready",
  mod_version: "0.18.0",
  targets: [{ kind: "lunar", state: "ready", label: "Lunar Client" }],
};
const LAUNCHED = {
  status: "launched",
  outcome: { useExternalOverlay: false, action: "game_launch_requested" },
  generation: 1,
};

let pendingLaunch = null;
/** What launch_progress answers; each test swaps it. */
let progressReply = () => ({ stage: "fired", percent: 10 });
const replies = {
  refresh_setup: () => READY_LUNAR,
  open_info: () => ({ version: "0.18.0-next.2", updated: false, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => PREFS,
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  launch_lunar: () => new Promise((resolve) => (pendingLaunch = resolve)),
  launch_progress: () => progressReply(),
  lobby_state: () => ({ kind: "unavailable" }),
  abort_launch_session: () => ({ status: "ok", preferences: PREFS }),
  reset_session_end: () => ({ status: "ok", preferences: PREFS }),
  quit_app: () => null,
  open_launcher: () => null,
};

const app = await bootWindow({ replies });
const { el, waitFor, tick, countCalls } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);
const hero = () => $("#main .hero");

await waitFor(() => $('#main [data-act="play"]') != null, { label: "the ready home" });

/** Press the home's main key and answer the launch with a launched reply. */
async function launch(generation) {
  const before = countCalls("launch_lunar");
  hero().click();
  await waitFor(() => countCalls("launch_lunar") > before && pendingLaunch != null, { label: "the launch call" });
  const resolve = pendingLaunch;
  pendingLaunch = null;
  resolve({ ...LAUNCHED, generation });
}

/** Let at least `n` more progress polls answer (600 ms apart). */
async function polls(n) {
  const before = countCalls("launch_progress");
  await waitFor(() => countCalls("launch_progress") >= before + n, { label: `${n} progress polls`, timeout: 600 * (n + 2) });
  await tick(20);
}

test("settled, then the didn't-load verdict: no quit, home shows Cobblify didn't load", async () => {
  progressReply = () => ({ stage: "settled", percent: 100 });
  await launch(1);
  await polls(2);
  assert.equal(countCalls("quit_app"), 0, "a quiet log is not Cobblify loading");

  progressReply = () => ({ stage: "settled", percent: 100, outcome: "not_loaded" });
  await waitFor(() => hero()?.dataset.act === "play" && /Try again/.test(hero().textContent), {
    label: "Try again",
    timeout: 3000,
  });
  assert.equal($("#main .heros .under.wait")?.textContent.trim(), "Cobblify didn't load");
  await tick(700);
  assert.equal(countCalls("quit_app"), 0, "the outcome is shown, the launcher stays open");
});

test("settled without loaded never quits; loaded quits exactly once", async () => {
  progressReply = () => ({ stage: "settled", percent: 100 });
  await launch(2);
  await polls(3);
  assert.equal(countCalls("quit_app"), 0, "no quit before Cobblify is known to have loaded");

  progressReply = () => ({ stage: "settled", percent: 100, loaded: true });
  await waitFor(() => countCalls("quit_app") === 1, { label: "the quit", timeout: 2000 });
  await polls(2);
  assert.equal(countCalls("quit_app"), 1, "exactly once across duplicate loaded polls");
});

test("unconfirmed launch: a snapshot of the game, acknowledged before progress says loaded, quits; never the manual copy again", async () => {
  // home again: the last session's game ends
  replies.lobby_state = () => ({ kind: "session_ended", reason: "game_session_ended" });
  await waitFor(() => $('#main [data-act="play"]') != null, { label: "home", timeout: 3000 });
  replies.lobby_state = () => ({ kind: "unavailable" });
  progressReply = () => ({ stage: "fired", percent: 10 });
  const quits = countCalls("quit_app");
  replies.acknowledge_lobby_snapshot = () => ({});
  const before = countCalls("launch_lunar");
  hero().click();
  await waitFor(() => countCalls("launch_lunar") > before && pendingLaunch != null, { label: "the launch call" });
  const resolve = pendingLaunch;
  pendingLaunch = null;
  resolve({ status: "launched", outcome: { useExternalOverlay: false, action: "launch_unconfirmed" }, generation: 3 });
  await waitFor(() => el("joining-sub").textContent.startsWith("Lunar is launching"), { label: "the manual screen", timeout: 3000 });
  const menu = {
    v: 2, seq: 1, jvmPid: 42, jvmStartTimeMs: 1_700_000_000_000, context: "MENU", inHypixel: false,
    self: null, mode: null, partyCount: null, yourParty: [], players: [], teams: [],
  };
  let n = 0;
  replies.lobby_state = () => (n++ % 2 ? { kind: "unavailable", reason: "process_unavailable" } : { kind: "snapshot", snapshot: menu, token: n });
  await waitFor(() => countCalls("quit_app") > quits, { label: "the quit", timeout: 3000 });
  await waitFor(() => el("joining-title").textContent === "Join Hypixel", { label: "Join Hypixel" });
  await tick(600);
  assert.equal(el("joining-title").textContent, "Join Hypixel", "an unavailable poll never brings back the manual screen");
  replies.lobby_state = () => ({ kind: "unavailable" });
});
