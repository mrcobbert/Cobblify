import test from "node:test";
import assert from "node:assert/strict";

import { addFor, blocksLaunch, homeModel, launchHero, pickSelected, sheetModel } from "./ui/model.js";

const lunarReady = { state: "ready", targets: [{ kind: "lunar", state: "ready" }, { kind: "forge", state: "absent", issue: "missing_launcher" }] };
const bothReady = { state: "ready", targets: [{ kind: "lunar", state: "ready" }, { kind: "forge", state: "ready" }] };
const nothing = {
  state: "blocked",
  targets: [
    { kind: "lunar", state: "absent", issue: "missing_launcher" },
    { kind: "forge", state: "absent", issue: "missing_launcher" },
  ],
};
const prismChoose = {
  state: "ready",
  targets: [
    { kind: "lunar", state: "ready" },
    { kind: "forge", state: "absent", action: "choose", candidates: [{ id: "a", name: "Hypixel" }, { id: "b", name: "PvP" }] },
  ],
};

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

test("the remembered client is kept while it is ready", () => {
  assert.equal(pickSelected(["lunar", "prism"], "prism"), "prism");
  assert.equal(pickSelected(["lunar"], "prism"), "lunar");
  assert.equal(pickSelected([], "prism"), null);
});

test("a ready client shows Launch, with Add for the missing one", () => {
  const m = homeModel({ status: lunarReady, selected: "lunar" });
  assert.equal(m.solo, false);
  assert.deepEqual(m.hero, { kind: "play" });
  assert.deepEqual(m.clients, ["lunar"]);
  assert.deepEqual(m.add, { id: "prism", act: "get", installed: false });
});

test("both ready: the menu lists both and remembers the choice", () => {
  const m = homeModel({ status: bothReady, selected: "prism" });
  assert.deepEqual(m.clients, ["lunar", "prism"]);
  assert.equal(m.sel, "prism");
  assert.equal(m.add, null);
});

test("a brand-new player gets the interim setup key, alone and without the island", () => {
  const m = homeModel({ status: nothing });
  assert.equal(m.solo, true);
  assert.equal(m.hero.label, "Get Lunar");
  assert.equal(m.hero.action.kind, "download");
  assert.deepEqual(m.keys.map((k) => k.label), ["Get Prism"]);
});

test("loading, a top-level error and an action-less issue", () => {
  assert.deepEqual(homeModel({ status: { state: "loading", targets: [] } }).hero, { kind: "wait", label: "Checking" });
  const err = homeModel({ status: { state: "error", message: "boom", targets: [] } });
  assert.equal(err.hero.label, "Try again");
  assert.equal(err.hero.action.kind, "refresh");
  assert.match(err.said, /boom/);
  const running = homeModel({ status: { state: "blocked", targets: [{ kind: "lunar", state: "blocked", issue: "running_lunar", message: "Quit" }] } });
  assert.deepEqual(running.hero, { kind: "wait", label: "Quit Lunar" });
});

test("Prism instance choice: a lead list and Set up Prism on the picked one", () => {
  const solo = homeModel({ status: { state: "blocked", targets: prismChoose.targets.slice(1) }, picked: "b" });
  assert.equal(solo.lead.kind, "choose");
  assert.equal(solo.lead.picked, "b");
  assert.deepEqual(solo.hero.action, { kind: "setup_prism", candidateId: "b" });

  const fromHome = homeModel({ status: prismChoose, adding: "prism" });
  assert.equal(fromHome.sel, "prism");
  assert.equal(fromHome.lead.picked, "a");
  assert.deepEqual(fromHome.keys, [{ label: "Cancel", act: "cancel-add" }]);
  assert.equal(addFor(prismChoose, "prism").act, "add");
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

test("settings rows follow the state", () => {
  const s = sheetModel({ status: lunarReady, update: { state: "current", autoUpdateEnabled: true, updateChannel: "stable" }, version: "0.18.0-next.1" });
  assert.equal(s.locked, false);
  assert.deepEqual(s.version, { label: "Cobblify 0.18.0-next.1", word: "Up to date", key: { label: "Check", act: "update", action: "check" } });
  assert.equal(s.autoUpdate.on, true);
  assert.equal(s.dev.on, false);
  assert.equal(s.lunarRepair, true);
  assert.equal(s.prismAdd.act, "get");

  const both = sheetModel({ status: bothReady, update: { state: "ready", availableVersion: "0.18.1" } });
  assert.equal(both.prismAdd, null);
  assert.deepEqual(both.version, { label: "Cobblify 0.18.1", word: "ready", key: { label: "Restart", act: "update", action: "install" } });

  const prismOnly = sheetModel({ status: { state: "ready", targets: [{ kind: "lunar", state: "absent", issue: "missing_launcher" }, { kind: "forge", state: "ready" }] } });
  assert.equal(prismOnly.lunarRepair, false);

  const locked = sheetModel({ status: lunarReady, launch: { active: true } });
  assert.equal(locked.locked, true);
  assert.equal(locked.reason, "Locked while launching");
  assert.equal(locked.overlay.disabled, true);
  assert.equal(locked.dev.disabled, true);
  assert.equal(locked.autoUpdate.disabled, false);

  assert.equal(sheetModel({ status: lunarReady, update: { state: "error" } }).version.word, "Check failed");
});

test("the dev switch keeps the click while the save is in flight", () => {
  const s = sheetModel({ status: lunarReady, update: { updateChannel: "stable", channelSaving: true }, devPending: true });
  assert.equal(s.dev.on, true);
  assert.equal(s.dev.disabled, true);
});
