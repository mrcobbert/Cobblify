import test from "node:test";
import assert from "node:assert/strict";

import { CHECKS, PRISM_CHECKS, addFor, blocksLaunch, homeModel, launchHero, pickSelected, setupPct, sheetModel, stuckAfter } from "./ui/model.js";

/* status targets shaped like main.rs TargetStatus (installed / set_up / opened_once / instances) */
const lunarT = (o = {}) => ({ kind: "lunar", state: "absent", issue: "not_set_up", installed: true, set_up: false, opened_once: true, ...o });
const forgeT = (o = {}) => ({ kind: "forge", state: "absent", action: "none", installed: true, set_up: false, instances: [], ...o });
const LUNAR_READY = lunarT({ state: "ready", issue: undefined, set_up: true });
const FORGE_READY = forgeT({ state: "ready", set_up: true });
const LUNAR_MISSING = lunarT({ issue: "missing_launcher", installed: false, opened_once: false });
const FORGE_MISSING = forgeT({ issue: "missing_launcher", installed: false });
const INSTANCES = [
  { id: "pvp", name: "PvP Pack", detail: "Forge 11.15.1.2318", last_launch_ms: 300 },
  { id: "hyp", name: "Hypixel 1.8.9", detail: "Forge 11.15.1.2318", last_launch_ms: 200 },
  { id: "sky", name: "Skyblock QoL", detail: "Forge 11.15.1.2318", last_launch_ms: 0 },
];
const st = (lunar, forge) => ({ state: "blocked", targets: [lunar, forge] });

const lunarReady = { state: "ready", targets: [LUNAR_READY, FORGE_MISSING] };
const bothReady = { state: "ready", targets: [LUNAR_READY, FORGE_READY] };
const nothing = st(LUNAR_MISSING, FORGE_MISSING);
const snap = (kind, check, o = {}) => ({ gen: 1, kind, check, phase: "running", ...o });
const setupOf = (s, stuckAt = null) => ({ snap: s, stuckAt });

// ── launch ─────────────────────────────────────────────────────────────────────────────────

test("launch phases follow the backend's stages", () => {
  assert.deepEqual(launchHero("lunar", "fired"), { kind: "prog", label: "Opening Lunar", pct: 10 });
  assert.deepEqual(launchHero("prism", "forge_fired"), { kind: "prog", label: "Opening Prism", pct: 10 });
  assert.deepEqual(launchHero("lunar", "attached").label, "Starting Minecraft");
  assert.deepEqual(launchHero("lunar", "discovered").label, "Loading mods");
  assert.deepEqual(launchHero("lunar", "mixing"), { kind: "prog", label: "Adding Cobblify", pct: 85 });
  assert.deepEqual(launchHero("lunar", "settled"), { kind: "wait", label: "Join Hypixel" });
  assert.deepEqual(launchHero("lunar", null).label, "Opening Lunar");
});

test("the game being up always reads Join Hypixel, never Joining: there is no auto-join", () => {
  assert.deepEqual(launchHero("prism", "forge_settled"), { kind: "wait", label: "Join Hypixel" });
  for (const stage of ["settled", "forge_settled"]) {
    assert.doesNotMatch(launchHero("lunar", stage).label, /Joining/);
  }
});

test("a made instance's first launch shows Downloading Minecraft at 22%", () => {
  assert.deepEqual(launchHero("prism", "forge_downloading"), { kind: "prog", label: "Downloading Minecraft", pct: 22 });
  const m = homeModel({
    status: bothReady,
    selected: "prism",
    launch: { kind: "forge", phase: "loading", stage: "forge_downloading", showCancel: true },
  });
  assert.deepEqual(m.hero, { kind: "prog", label: "Downloading Minecraft", pct: 22 });
  assert.equal(m.said, "Downloading Minecraft");
  assert.deepEqual(m.keys, [{ label: "Cancel", act: "cancel" }]);
});

test("wrong Lunar version: Launch stays, with the Set Lunar to 1.8.9 under-row", () => {
  const m = homeModel({ status: bothReady, selected: "lunar", launch: { outcome: "wrong_version", outcomeKind: "lunar" } });
  assert.equal(m.launching, false);
  assert.deepEqual(m.hero, { kind: "play" });
  assert.deepEqual(m.under, { kind: "setLunar" });
  assert.equal(m.said, "Lunar isn't set to 1.8.9.");
});

test("Cobblify didn't load: Try again, with the not-loaded under-row", () => {
  const m = homeModel({ status: bothReady, selected: "prism", launch: { outcome: "not_loaded", outcomeKind: "forge" } });
  assert.deepEqual(m.hero, { kind: "act", label: "Try again", act: "play" });
  assert.deepEqual(m.under, { kind: "notLoaded" });
  assert.equal(m.said, "Cobblify didn't load. Minecraft started without Cobblify.");
});

test("an outcome stays with the client that launched, and gives way to a new launch", () => {
  const other = homeModel({ status: bothReady, selected: "prism", launch: { outcome: "wrong_version", outcomeKind: "lunar" } });
  assert.deepEqual(other.hero, { kind: "play" });
  assert.equal(other.under, null);
  const relaunch = homeModel({
    status: bothReady,
    selected: "lunar",
    launch: { kind: "lunar", phase: "loading", busy: true, outcome: "not_loaded", outcomeKind: "lunar" },
  });
  assert.equal(relaunch.hero.label, "Opening Lunar");
  assert.equal(relaunch.under, null);
  assert.equal(homeModel({ status: lunarReady }).under, null);
  assert.equal(homeModel({ status: nothing }).under, null);
});

test("launching: the key is the progress, Cancel while it may be cancelled", () => {
  const m = homeModel({
    status: bothReady,
    selected: "lunar",
    launch: { kind: "forge", phase: "loading", stage: "forge_mixing", showCancel: true },
  });
  assert.equal(m.sel, "prism");
  assert.equal(m.launching, true);
  assert.deepEqual(m.hero, { kind: "prog", label: "Adding Cobblify", pct: 85 });
  assert.deepEqual(m.keys, [{ label: "Cancel", act: "cancel" }]);
  const busy = homeModel({ status: lunarReady, launch: { kind: "lunar", phase: "loading", busy: true } });
  assert.equal(busy.hero.label, "Opening Lunar");
});

test("a rejected launch shows Try again with the reason for screen readers", () => {
  const m = homeModel({ status: lunarReady, launch: { prefError: "wait" } });
  assert.deepEqual(m.hero, { kind: "act", label: "Try again", act: "play" });
  assert.equal(m.said, "wait");
});

test("a damaged preferences file asks for repair before Launch", () => {
  assert.equal(homeModel({ status: lunarReady, launch: { prefHealth: "invalid" } }).hero.act, "repair-pref");
});

test("a required update replaces Launch", () => {
  assert.equal(blocksLaunch({ critical: true, state: "available" }), true);
  assert.equal(blocksLaunch({ critical: true, state: "installing" }), false);
  const dl = homeModel({ status: lunarReady, update: { critical: true, state: "downloading", availableVersion: "0.18.1", downloadedBytes: 42, sizeBytes: 100 } });
  assert.deepEqual(dl.hero, { kind: "prog", label: "Updating to 0.18.1", pct: 42 });
  const av = homeModel({ status: lunarReady, update: { critical: true, state: "critical_required", availableVersion: "0.18.1" } });
  assert.deepEqual(av.hero, { kind: "act", label: "Update to 0.18.1", act: "update", action: "download" });
});

// ── home ───────────────────────────────────────────────────────────────────────────────────

test("the remembered client is kept while it is set up", () => {
  assert.equal(pickSelected(["lunar", "prism"], "prism"), "prism");
  assert.equal(pickSelected(["lunar"], "prism"), "lunar");
  assert.equal(pickSelected([], "prism"), null);
});

test("a set-up client shows Launch and the whole island, with Add for the other one", () => {
  const m = homeModel({ status: lunarReady, selected: "lunar" });
  assert.equal(m.solo, false);
  assert.deepEqual(m.hero, { kind: "play" });
  assert.deepEqual(m.clients, ["lunar"]);
  assert.equal(m.island, null, "the whole island");
  assert.deepEqual(m.add, { id: "prism", installed: false });
  assert.deepEqual(addFor(st(LUNAR_READY, forgeT()), "prism"), { id: "prism", installed: true });
});

test("both set up: the menu lists both and remembers the choice", () => {
  const m = homeModel({ status: bothReady, selected: "prism" });
  assert.deepEqual(m.clients, ["lunar", "prism"]);
  assert.equal(m.sel, "prism");
  assert.equal(m.add, null);
});

test("set up counts a ready target from a reply without the 0.18 facts", () => {
  const old = { state: "ready", targets: [{ kind: "lunar", state: "ready" }] };
  assert.deepEqual(homeModel({ status: old }).hero, { kind: "play" });
});

test("a set-up client whose files couldn't be updated runs its setup again from Try again", () => {
  const m = homeModel({ status: st(lunarT({ state: "blocked", issue: "game_open", set_up: true }), FORGE_MISSING) });
  assert.deepEqual(m.hero, { kind: "act", label: "Try again", act: "repair-setup" });
  assert.equal(m.sel, "lunar");
  assert.equal(m.said, "Setup didn't finish. Play waits until setup finishes.");
});

test("Cobblify's files in an open Lunar only need updating: Repair runs setup (and its Closing Lunar), no failure", () => {
  for (const issue of ["running_lunar", "not_current"]) {
    const m = homeModel({ status: st(lunarT({ state: "blocked", issue, set_up: true }), FORGE_MISSING) });
    assert.deepEqual(m.hero, { kind: "act", label: "Repair", act: "repair-setup" }, issue);
    assert.equal(m.sel, "lunar");
    assert.equal(m.said, "Cobblify's files in Lunar need updating.", issue);
  }
});

test("just set up: the ready line with one client, nothing with two", () => {
  assert.equal(homeModel({ status: lunarReady, freshSetup: true }).said, "In game, Right Shift opens Cobblify's settings.");
  assert.equal(homeModel({ status: bothReady, freshSetup: true }).said, "");
  assert.equal(homeModel({ status: lunarReady }).said, "Launch Cobblify in Lunar Client");
});

test("loading and a top-level error", () => {
  const loading = homeModel({ status: { state: "loading", targets: [] } });
  assert.deepEqual(loading.hero, { kind: "wait", label: "Checking" });
  assert.equal(loading.solo, true);
  const err = homeModel({ status: { state: "error", message: "boom", targets: [] } });
  assert.deepEqual(err.hero, { kind: "act", label: "Try again", act: "refresh" });
  assert.match(err.said, /boom/);
});

// ── first run: Choose Your Setup ───────────────────────────────────────────────────────────

test("first run: Choose Your Setup, alone, no island, whatever is installed", () => {
  const combos = [
    [nothing, [false, false], "Choose a client: Cobblify runs inside Lunar Client or Prism Launcher, and neither is on this computer yet."],
    [st(lunarT(), FORGE_MISSING), [true, false], "Choose a client: Lunar Client is on this computer, the other one downloads first."],
    [st(LUNAR_MISSING, forgeT()), [false, true], "Choose a client: Prism Launcher is on this computer, the other one downloads first."],
    [st(lunarT(), forgeT()), [true, true], "Choose a client: Lunar Client and Prism Launcher are both on this computer."],
  ];
  for (const [status, [lunar, prism], said] of combos) {
    const m = homeModel({ status });
    assert.equal(m.solo, true);
    assert.deepEqual(m.hero, { kind: "setup" }, "the same key every time");
    assert.equal(m.island, null);
    assert.equal(m.under, null);
    assert.deepEqual(m.keys, []);
    assert.deepEqual(m.setupMenu, [{ id: "lunar", installed: lunar }, { id: "prism", installed: prism }]);
    assert.equal(m.said, said);
    assert.equal(m.sel, null);
  }
});

test("a never-opened Lunar counts as installed", () => {
  const m = homeModel({ status: st(lunarT({ opened_once: false }), FORGE_MISSING) });
  assert.equal(m.setupMenu[0].installed, true);
});

test("the install wait: the same key, Install <client> + Download under it", () => {
  const m = homeModel({ status: nothing, wait: "lunar" });
  assert.deepEqual(m.hero, { kind: "setup" });
  assert.deepEqual(m.under, { kind: "install", id: "lunar" });
  assert.equal(m.said, "Waiting for Lunar Client to be installed. Setup starts by itself once it is.");
  const p = homeModel({ status: nothing, wait: "prism" });
  assert.deepEqual(p.under, { kind: "install", id: "prism" });
  assert.equal(p.said, "Waiting for Prism Launcher to be installed. Setup starts by itself once it is.");
});

// ── setup ──────────────────────────────────────────────────────────────────────────────────

test("setup: the slim bar, one island part per check, the check's name read out", () => {
  const pcts = [4, 17, 33, 50, 67, 83];
  for (let check = 0; check < 6; check++) {
    const m = homeModel({ status: nothing, setup: setupOf(snap("lunar", check)) });
    assert.equal(m.solo, false, "the column has slid to the left");
    assert.equal(m.sel, "lunar");
    assert.equal(m.setup, true);
    assert.deepEqual(m.hero, { kind: "slim", label: "Setting up Lunar", pct: pcts[check] });
    assert.equal(setupPct(check), pcts[check]);
    assert.deepEqual(m.island, { built: check / 6, stuck: false });
    assert.equal(m.said, CHECKS[check] + ".");
    assert.deepEqual(m.keys, []);
    assert.equal(m.add, null);
  }
  const p = homeModel({ status: nothing, setup: setupOf(snap("forge", 3)) });
  assert.deepEqual(p.hero, { kind: "slim", label: "Setting up Prism", pct: 50 });
  assert.equal(p.sel, "prism");
  assert.equal(p.said, PRISM_CHECKS[3] + ".");
});

test("setup: the long checks name themselves", () => {
  const make = homeModel({ status: nothing, setup: setupOf(snap("forge", 3, { label: "making_instance" })) });
  assert.deepEqual(make.hero, { kind: "slim", label: "Making a 1.8.9 instance", pct: 50 });
  assert.equal(make.said, "Making a 1.8.9 instance.");
  assert.deepEqual(make.island, { built: 3 / 6, stuck: false });
  const java = homeModel({ status: nothing, setup: setupOf(snap("forge", 4, { label: "java" })) });
  assert.deepEqual(java.hero, { kind: "slim", label: "Downloading Java 8", pct: 67 });
  assert.equal(java.said, "Downloading Java 8.");
  const vanilla = homeModel({ status: nothing, setup: setupOf(snap("lunar", 4, { label: "vanilla" })) });
  assert.deepEqual(vanilla.hero, { kind: "slim", label: "Downloading Minecraft 1.8.9", pct: 67 });
  assert.equal(vanilla.said, "Downloading Minecraft 1.8.9.");
});

test("setup: the sign-in stops keep setup's progress, with Open, and hang no ghost", () => {
  const l = homeModel({ status: nothing, setup: setupOf(snap("lunar", 2, { phase: "stop", stop: "signin" })) });
  assert.deepEqual(l.hero, { kind: "slim", label: "Sign in to Lunar", pct: 33, open: { label: "Open", id: "lunar" } });
  assert.deepEqual(l.island, { built: 2 / 6 });
  assert.equal(l.said, "Sign in to Lunar. Lunar hasn't been signed in on this computer yet.");
  const p = homeModel({ status: nothing, setup: setupOf(snap("forge", 2, { phase: "stop", stop: "signin" }), 2) });
  assert.deepEqual(p.hero, { kind: "slim", label: "Sign in to Prism", pct: 33, open: { label: "Open", id: "prism" } });
  assert.deepEqual(p.island, { built: 2 / 6 }, "even on a resumed run, sign-in hangs no ghost");
  assert.equal(p.said, "Sign in to Prism. Prism has no Microsoft account on this computer yet.");
});

test("setup: a game open is Close Minecraft, the next part hanging grey", () => {
  const s = snap("lunar", 3, { phase: "stop", stop: "game_open" });
  const m = homeModel({ status: nothing, setup: setupOf(s, stuckAfter(null, s)) });
  assert.deepEqual(m.hero, { kind: "wait", label: "Close Minecraft" });
  assert.deepEqual(m.island, { built: 3 / 6, stuck: true });
  assert.equal(m.under, null);
  assert.equal(m.said, "Game open. Minecraft is running from Lunar. Setup finishes once it closes.");
  const p = homeModel({ status: nothing, setup: setupOf(snap("forge", 4, { phase: "stop", stop: "game_open" })) });
  assert.equal(p.said, "Game open. Minecraft is running. Setup finishes once it closes.");
});

test("setup: Closing Lunar at 50%, still hanging after a game closed", () => {
  const s = snap("lunar", 3, { phase: "stop", stop: "closing_lunar", label: "closing_lunar" });
  const m = homeModel({ status: nothing, setup: setupOf(s) });
  assert.deepEqual(m.hero, { kind: "slim", label: "Closing Lunar", pct: 50 });
  assert.deepEqual(m.island, { built: 3 / 6, stuck: false });
  assert.equal(m.said, "Closing Lunar.");
  // the game closed, Lunar is still running: the ghost keeps hanging until check 4 passes
  let at = stuckAfter(null, snap("lunar", 3, { phase: "stop", stop: "game_open" }));
  at = stuckAfter(at, s);
  assert.deepEqual(homeModel({ status: nothing, setup: setupOf(s, at) }).island, { built: 3 / 6, stuck: true });
  at = stuckAfter(at, snap("lunar", 4));
  assert.equal(at, null, "the part dropped once its check passed");
});

test("setup failed: Try again, the failed check's part hanging; the resume keeps it until it passes", () => {
  const failed = snap("forge", 4, { phase: "failed", failedAt: 5, message: "busy jar" });
  const m = homeModel({ status: nothing, setup: setupOf(failed) });
  assert.deepEqual(m.hero, { kind: "act", label: "Try again", act: "retry-setup" });
  assert.deepEqual(m.island, { built: 4 / 6, stuck: true });
  assert.equal(m.under, null);
  assert.deepEqual(m.keys, []);
  assert.equal(m.said, "Setup didn't finish. Play waits until setup finishes.");

  let at = stuckAfter(null, failed);
  assert.equal(at, 4);
  const resumed = { ...snap("forge", 4), gen: 2 };
  at = stuckAfter(at, resumed);
  const r = homeModel({ status: nothing, setup: setupOf(resumed, at) });
  assert.deepEqual(r.hero, { kind: "slim", label: "Setting up Prism", pct: 67 });
  assert.deepEqual(r.island, { built: 4 / 6, stuck: true });
  at = stuckAfter(at, { ...snap("forge", 5), gen: 2 });
  assert.deepEqual(homeModel({ status: nothing, setup: setupOf({ ...snap("forge", 5), gen: 2 }, at) }).island, { built: 5 / 6, stuck: false });
});

test("setup done: the bar holds full while the last part and the bed land", () => {
  const m = homeModel({ status: nothing, setup: setupOf(snap("lunar", 6, { phase: "done" })) });
  assert.deepEqual(m.hero, { kind: "slim", label: "Setting up Lunar", pct: 100 });
  assert.deepEqual(m.island, { built: 1 });
  assert.equal(m.said, CHECKS[5] + ".");
  // then home: Launch, the whole island, the ready line
  const ready = homeModel({ status: lunarReady, selected: "lunar", freshSetup: true });
  assert.deepEqual(ready.hero, { kind: "play" });
  assert.equal(ready.island, null);
});

test("adding a second client rebuilds the island from nothing, and hides the menu", () => {
  const m = homeModel({ status: lunarReady, selected: "lunar", setup: setupOf(snap("forge", 0)) });
  assert.deepEqual(m.clients, ["lunar", "prism"]);
  assert.equal(m.sel, "prism");
  assert.deepEqual(m.island, { built: 0, stuck: false });
  assert.equal(m.hero.kind, "slim");
});

test("a setup takes over a pending add-wait's row", () => {
  const m = homeModel({ status: lunarReady, setup: setupOf(snap("lunar", 1)), addWait: "prism" });
  assert.equal(m.under, null);
});

// ── the Prism pick ─────────────────────────────────────────────────────────────────────────

test("the Prism pick on a first run: alone, the most recently played preselected", () => {
  const status = st(LUNAR_MISSING, forgeT({ instances: INSTANCES }));
  const m = homeModel({ status, pick: { picked: null } });
  assert.equal(m.solo, true);
  assert.equal(m.island, null);
  assert.equal(m.sel, "prism");
  assert.deepEqual(m.hero, { kind: "act", label: "Set up Prism", act: "setup-prism" });
  assert.equal(m.lead.kind, "choose");
  assert.equal(m.lead.picked, "pvp", "the backend sorts most recently played first");
  assert.deepEqual(m.lead.candidates[1], { id: "hyp", name: "Hypixel 1.8.9", detail: "Forge 11.15.1.2318" });
  assert.equal(m.said, "Choose an instance. Prism has 3 instances that can run Cobblify.");
  assert.deepEqual(m.keys, []);
  assert.equal(homeModel({ status, pick: { picked: "sky" } }).lead.picked, "sky");
});

test("the Prism pick from home: the normal column, the island greyed", () => {
  const status = { state: "ready", targets: [LUNAR_READY, forgeT({ instances: INSTANCES.slice(0, 1) })] };
  const m = homeModel({ status, selected: "lunar", pick: { picked: "pvp" } });
  assert.equal(m.solo, false);
  assert.deepEqual(m.island, { built: 1, stuck: true, grey: true });
  assert.deepEqual(m.clients, ["lunar", "prism"]);
  assert.equal(m.sel, "prism");
  assert.equal(m.said, "Choose an instance. Prism has 1 instance that can run Cobblify.");
});

// ── add from home ──────────────────────────────────────────────────────────────────────────

test("the add-wait: Install Prism + Download under Launch, only while Launch shows", () => {
  const m = homeModel({ status: lunarReady, selected: "lunar", addWait: "prism" });
  assert.deepEqual(m.hero, { kind: "play" });
  assert.deepEqual(m.under, { kind: "install", id: "prism" });
  assert.equal(m.said, "Launch Cobblify in Lunar Client", "home's line is unchanged");
  const launching = homeModel({ status: lunarReady, addWait: "prism", launch: { kind: "lunar", phase: "loading", busy: true } });
  assert.equal(launching.under, null);
  const failed = homeModel({ status: lunarReady, addWait: "prism", launch: { outcome: "not_loaded", outcomeKind: "lunar" } });
  assert.deepEqual(failed.under, { kind: "notLoaded" }, "an outcome's row wins");
});

// ── settings ───────────────────────────────────────────────────────────────────────────────

test("settings rows follow the state", () => {
  const s = sheetModel({ status: lunarReady, update: { state: "current", autoUpdateEnabled: true, updateChannel: "stable" }, version: "0.18.0-next.1" });
  assert.equal(s.locked, false);
  assert.deepEqual(s.version, { label: "Cobblify 0.18.0-next.1", word: "Up to date", key: { label: "Check", act: "update", action: "check" } });
  assert.equal(s.autoUpdate.on, true);
  assert.equal(s.dev.on, false);
  assert.deepEqual(s.lunar, { label: "Repair", act: "repair-lunar", disabled: false });
  assert.deepEqual(s.prism, { label: "Add", act: "sheet-add", disabled: false, add: { id: "prism", installed: false } });

  const both = sheetModel({ status: bothReady, update: { state: "ready", availableVersion: "0.18.1" } });
  assert.deepEqual(both.prism, { label: "Change", act: "sheet-change", disabled: false });
  assert.deepEqual(both.version, { label: "Cobblify 0.18.1", word: "ready", key: { label: "Restart", act: "update", action: "install" } });

  const prismOnly = sheetModel({ status: { state: "ready", targets: [LUNAR_MISSING, FORGE_READY] } });
  assert.equal(prismOnly.lunar, null, "Repair only for a set-up Lunar");
  assert.equal(prismOnly.prism.label, "Change");

  const locked = sheetModel({ status: lunarReady, launch: { active: true } });
  assert.equal(locked.locked, true);
  assert.equal(locked.reason, "Locked while launching");
  assert.equal(locked.overlay.disabled, true);
  assert.equal(locked.dev.disabled, true);
  assert.equal(locked.autoUpdate.disabled, false);
  assert.equal(locked.lunar.disabled, true);
  assert.equal(locked.prism.disabled, true);

  const setup = sheetModel({ status: lunarReady, setupActive: true });
  assert.equal(setup.locked, false, "setup locks the client rows only");
  assert.equal(setup.lunar.disabled, true);
  assert.equal(setup.prism.disabled, true);
  assert.equal(setup.overlay.disabled, false);

  assert.equal(sheetModel({ status: lunarReady, update: { state: "error" } }).version.word, "Check failed");
  assert.equal(sheetModel({ status: { state: "loading", targets: [] } }).prism, null);
});

test("the dev switch keeps the click while the save is in flight", () => {
  const s = sheetModel({ status: lunarReady, update: { updateChannel: "stable", channelSaving: true }, devPending: true });
  assert.equal(s.dev.on, true);
  assert.equal(s.dev.disabled, true);
});
