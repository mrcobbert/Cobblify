import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * Adding the other client from home, in the real window: Lunar is set up, Prism isn't installed. "Add Prism" opens
 * Prism's download page and waits (Choose Your Setup over Install Prism + Download, Launch paused); it follows through only on an idle home
 * (not while the sheet is open). Prism then has several instances: the backend's `choose_instance` brings up the
 * pick with the most recently played preselected. Then settings: Repair re-runs Lunar's setup, Change re-picks
 * Prism's instance. One boot per file; the tests run in order.
 */

const lunar = (o) => ({ kind: "lunar", state: "ready", message: "", action: "none", candidates: [], has_setup_folder: false, installed: true, set_up: true, opened_once: true, ...o });
const forge = (o) => ({ kind: "forge", state: "absent", issue: "missing_launcher", message: "", action: "none", candidates: [], has_setup_folder: false, installed: false, set_up: false, instances: [], ...o });
const INSTANCES = [
  { id: "pvp", name: "PvP Pack", detail: "Forge 11.15.1.2318", last_launch_ms: 300 },
  { id: "hyp", name: "Hypixel 1.8.9", detail: "Forge 11.15.1.2318", last_launch_ms: 200 },
  { id: "sky", name: "Skyblock QoL", detail: "Forge 11.15.1.2318", last_launch_ms: 0 },
];
const PREFS = { useExternalOverlay: true, health: "valid" };
const ready = (f) => ({ state: "ready", message: "", mod_version: "0.17.0", targets: [lunar(), f] });
const PRISM_MISSING = ready(forge());
const PRISM_FRESH = ready(forge({ issue: undefined, installed: true }));
const PRISM_SEVERAL = ready(forge({ issue: undefined, installed: true, action: "choose", instances: INSTANCES }));
const BOTH = ready(forge({ state: "ready", issue: undefined, installed: true, set_up: true, instances: INSTANCES }));

let status = PRISM_MISSING;
let gen = 0;
/** What begin_setup does next; a test swaps it (the default starts a run). */
let begin = ({ kind }) => ({ gen: ++gen, kind, check: 0, phase: "running" });
const replies = {
  refresh_setup: () => status,
  status: () => status,
  setup_state: () => null,
  open_info: () => ({ version: "0.18.0-next.2", updated: false, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => PREFS,
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  get_launcher: ({ kind }) => ({ installed: false, download_url: kind }),
  open_launcher: () => null,
  begin_setup: (args) => begin(args),
};

const app = await bootWindow({ replies });
const { el, waitFor, tick, calls, emit } = app;
const home = el("app");
const sheet = el("sheet");
const $ = (s) => home.querySelector(s);
const hero = () => $("#main .heros .hero");
const under = () => $("#main .heros .under");
const said = () => $("#main .sr")?.textContent ?? "";
const callsOf = (cmd) => calls.filter((c) => c.command === cmd);
const progress = (kind, s) => emit("setup://progress", { gen, kind, ...s });
const openSheet = async () => {
  $('[data-act="settings"]').click();
  await waitFor(() => sheet.classList.contains("open"), { label: "the sheet" });
};
const playKey = () => $('#main [data-act="play"]');

await waitFor(() => playKey() != null, { label: "the ready home" });

test("the menu offers Add Prism with the download mark while Prism isn't installed", async () => {
  $(".caret").click();
  await waitFor(() => home.querySelector("#layer .madd") != null, { label: "the menu" });
  const add = home.querySelector("#layer .madd");
  assert.equal(add.textContent.trim(), "Add Prism");
  assert.equal(add.getAttribute("aria-label"), "Add Prism, download");
  assert.ok(add.querySelector(".ck svg"), "the download mark");
});

test("Add Prism opens its download page; Launch pauses for Choose Your Setup over Install Prism + Download", async () => {
  home.querySelector("#layer .madd").click();
  await waitFor(() => callsOf("get_launcher").length === 1, { label: "Prism's download page" });
  assert.deepEqual(callsOf("get_launcher")[0].args, { kind: "forge" });
  await waitFor(() => under()?.classList.contains("wait"), { label: "the add-wait row" });
  assert.equal(playKey(), null, "Launch is paused");
  assert.ok(hero().classList.contains("setup"), "Choose Your Setup");
  assert.equal($(".caret"), null, "no arrow: the whole key opens the menu");
  assert.equal(under().querySelector(".t").textContent, "Install Prism");
  under().querySelector('[data-act="download"]').click();
  await waitFor(() => callsOf("get_launcher").length === 2, { label: "the page again" });
});

test("the wait's menu: Lunar plain, Prism with the download mark and no tick; Prism opens its page again", async () => {
  hero().click();
  await waitFor(() => home.querySelector("#layer .menu") != null, { label: "the menu" });
  const rows = () => [...home.querySelectorAll("#layer .mrow")];
  assert.deepEqual(rows().map((r) => r.textContent.trim()), ["Lunar Client", "Prism Launcher"]);
  assert.equal(rows()[0].querySelector(".ck svg"), null, "Lunar: no mark, no tick");
  assert.ok(rows()[1].querySelector(".ck svg"), "Prism: the download mark");
  assert.equal(rows()[1].getAttribute("aria-label"), "Prism Launcher, download");
  rows()[1].click();
  await waitFor(() => callsOf("get_launcher").length === 3, { label: "Prism's page again" });
  await tick(20);
  assert.ok(hero().classList.contains("setup"), "still waiting");
  assert.equal(under().querySelector(".t").textContent, "Install Prism");
});

test("picking Lunar in the wait's menu returns to Launch Cobblify and drops the wait", async () => {
  hero().click();
  await waitFor(() => home.querySelector("#layer .menu") != null, { label: "the menu" });
  home.querySelector('#layer .mrow[data-id="lunar"]').click();
  await waitFor(() => playKey() != null, { label: "Launch for Lunar" });
  assert.equal(under(), null, "the install row is gone");
  // add again, for the follow-through below
  $(".caret").click();
  await waitFor(() => home.querySelector("#layer .madd") != null, { label: "the menu" });
  home.querySelector("#layer .madd").click();
  await waitFor(() => under()?.classList.contains("wait"), { label: "the add-wait row again" });
});

test("installed while the sheet is open: it waits, and follows through once the sheet closes", async () => {
  await openSheet();
  status = PRISM_FRESH;
  // a Prism just installed has no instances yet; the backend still finds several by the time setup begins
  begin = () => {
    status = PRISM_SEVERAL;
    throw "choose_instance";
  };
  const refreshes = callsOf("refresh_setup").length;
  await waitFor(() => callsOf("refresh_setup").length > refreshes, { label: "the install poll", timeout: 4000 });
  await tick(50);
  assert.equal(callsOf("begin_setup").length, 0, "no setup while the sheet is open");
  sheet.querySelector('[data-act="close"]').click();
  await waitFor(() => callsOf("begin_setup").length === 1, { label: "the follow-through" });
  assert.deepEqual(callsOf("begin_setup")[0].args, { kind: "forge" });
});

test("several instances: the pick, from home, the most recently played preselected", async () => {
  await waitFor(() => $('#main [role="radiogroup"]') != null, { label: "the instance pick" });
  const group = $('#main [role="radiogroup"]');
  assert.equal(group.getAttribute("aria-label"), "Prism instances");
  assert.equal($(".body.home.solo"), null, "from home the column stays on the left");
  const rows = () => [...group.ownerDocument.querySelectorAll('#main .choice[role="radio"]')];
  assert.deepEqual(rows().map((r) => r.querySelector(".cx").textContent), [
    "PvP PackForge 11.15.1.2318",
    "Hypixel 1.8.9Forge 11.15.1.2318",
    "Skyblock QoLForge 11.15.1.2318",
  ]);
  assert.deepEqual(rows().map((r) => r.getAttribute("aria-checked")), ["true", "false", "false"]);
  assert.ok(rows()[0].querySelector(".ck svg"), "the accent tick");
  assert.equal(hero().textContent.trim(), "Set up Prism");
  assert.equal(said(), "Choose an instance. Prism has 3 instances that can run Cobblify.");
  assert.equal(under(), null, "the add-wait is done");

  rows()[1].click();
  await waitFor(() => rows()[1].getAttribute("aria-checked") === "true", { label: "the new pick" });
  begin = ({ kind }) => ({ gen: ++gen, kind, check: 0, phase: "running" });
  hero().click();
  await waitFor(() => callsOf("begin_setup").length === 2, { label: "Prism's setup" });
  assert.deepEqual(callsOf("begin_setup")[1].args, { kind: "forge", instanceId: "hyp" });
  await waitFor(() => $("#main .hero.slim")?.getAttribute("aria-label") === "Setting up Prism", { label: "Prism's slim bar" });
});

test("Prism's setup through a made instance's long check, to home with both clients", async () => {
  progress("forge", { check: 3, phase: "running", label: "making_instance" });
  await waitFor(() => under()?.textContent.trim() === "Making a 1.8.9 instance", { label: "the long check" });
  assert.equal(said(), "Making a 1.8.9 instance.");
  progress("forge", { check: 4, phase: "running", label: "java" });
  await waitFor(() => under()?.textContent.trim() === "Downloading Java 8", { label: "Java" });
  status = BOTH;
  progress("forge", { check: 6, phase: "done" });
  await waitFor(() => playKey()?.getAttribute("aria-label") === "Launch Cobblify in Prism Launcher", { label: "Launch in Prism" });
  assert.equal(said(), "", "two clients: no ready line");
  $(".caret").click();
  await waitFor(() => home.querySelectorAll("#layer .mrow").length === 2, { label: "both clients" });
  assert.equal(home.querySelector("#layer .madd"), null, "nothing left to add");
  home.querySelector('#layer .mrow[data-id="lunar"]').click();
  await waitFor(() => playKey()?.getAttribute("aria-label") === "Launch Cobblify in Lunar Client", { label: "Lunar selected" });
});

test("settings: Repair re-runs Lunar's setup through the flow; the client rows lock meanwhile", async () => {
  await openSheet();
  assert.equal(sheet.querySelector('[data-row="lunar"] .key').textContent.trim(), "Repair");
  assert.equal(sheet.querySelector('[data-row="prism"] .key').textContent.trim(), "Change");
  sheet.querySelector('[data-row="lunar"] .key').click();
  await waitFor(() => callsOf("begin_setup").length === 3, { label: "Lunar's setup" });
  assert.deepEqual(callsOf("begin_setup")[2].args, { kind: "lunar" });
  assert.equal(sheet.classList.contains("open"), false, "the sheet closes first");
  await waitFor(() => $("#main .hero.slim")?.getAttribute("aria-label") === "Setting up Lunar", { label: "Lunar's slim bar" });

  await openSheet();
  assert.equal(sheet.querySelector('[data-row="lunar"] .key').disabled, true);
  assert.equal(sheet.querySelector('[data-row="prism"] .key').disabled, true);
  sheet.querySelector('[data-act="close"]').click();

  progress("lunar", { check: 6, phase: "done" });
  await waitFor(() => playKey()?.getAttribute("aria-label") === "Launch Cobblify in Lunar Client", { label: "home again" });
});

test("settings: Change re-picks Prism's instance, then sets Prism up on it", async () => {
  await openSheet();
  sheet.querySelector('[data-row="prism"] .key').click();
  await waitFor(() => $('#main [role="radiogroup"]') != null, { label: "the pick" });
  assert.equal(sheet.classList.contains("open"), false);
  assert.equal(home.querySelector('#main .choice[aria-checked="true"]').dataset.id, "pvp");
  hero().click();
  await waitFor(() => callsOf("begin_setup").length === 4, { label: "Prism's setup" });
  assert.deepEqual(callsOf("begin_setup")[3].args, { kind: "forge", instanceId: "pvp" });
  // the run's last event can overtake begin_setup's own reply
  progress("forge", { check: 6, phase: "done" });
  await waitFor(() => playKey()?.getAttribute("aria-label") === "Launch Cobblify in Prism Launcher", { label: "home in Prism" });
});
