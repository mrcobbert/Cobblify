import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * A brand-new player in the real window: no Lunar, no Prism, nothing set up, a fresh install. Choose Your Setup,
 * Lunar's download page and the install wait, then Lunar's setup driven by scripted `setup://progress` events
 * through the sign-in stop, Closing Lunar, a game left open, a failure and Try again, to Launch. One boot per file;
 * the tests run in order.
 */

const lunar = (o) => ({ kind: "lunar", state: "absent", issue: "missing_launcher", message: "", action: "none", candidates: [], has_setup_folder: false, installed: false, set_up: false, opened_once: false, ...o });
const forge = (o) => ({ kind: "forge", state: "absent", issue: "missing_launcher", message: "", action: "none", candidates: [], has_setup_folder: false, installed: false, set_up: false, instances: [], ...o });
const NOTHING = { state: "blocked", message: "", mod_version: "0.17.0", targets: [lunar(), forge()] };
const LUNAR_INSTALLED = { ...NOTHING, targets: [lunar({ issue: "not_set_up", installed: true }), forge()] };
const LUNAR_SET_UP = { state: "ready", message: "", mod_version: "0.17.0", targets: [lunar({ state: "ready", issue: undefined, installed: true, set_up: true, opened_once: true }), forge()] };

let status = NOTHING;
let gen = 0;
const replies = {
  refresh_setup: () => status,
  status: () => status,
  setup_state: () => null,
  open_info: () => ({ version: "0.18.0-next.2", updated: false, fresh: true }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "missing" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: false, updateChannel: "stable", health: "missing" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  get_launcher: ({ kind }) => ({ installed: false, download_url: kind }),
  open_launcher: () => null,
  begin_setup: ({ kind }) => ({ gen: ++gen, kind, check: 0, phase: "running" }),
  resume_setup: ({ kind }) => ({ gen: ++gen, kind, check: 5, phase: "running" }),
};

const app = await bootWindow({ replies });
const { el, waitFor, tick, calls, document, window, emit, windowShows } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);
const hero = () => $("#main .heros .hero");
const under = () => $("#main .heros .under");
const said = () => $("#main .sr")?.textContent ?? "";
const callsOf = (cmd) => calls.filter((c) => c.command === cmd);
const progress = (s) => emit("setup://progress", { gen, kind: "lunar", ...s });

await waitFor(() => hero()?.classList.contains("setup"), { label: "Choose Your Setup" });

test("Choose Your Setup stands alone, with no island, no arrow and no What's New, and the window is shown", async () => {
  assert.ok($(".body.home.solo"), "the solo layout");
  assert.match(hero().textContent, /Choose Your Setup/);
  assert.equal(hero().getAttribute("aria-haspopup"), "menu");
  assert.equal($(".caret"), null, "no arrow: the whole key opens the menu");
  assert.equal($("#isl").style.display, "none", "no island before a client is set up");
  assert.equal(under(), null);
  assert.equal(said(), "Choose a client: Cobblify runs inside Lunar Client or Prism Launcher, and neither is on this computer yet.");
  await waitFor(() => windowShows.count === 1, { label: "the hidden window shown" });
  await tick(50);
  assert.equal($(".modal"), null, "a fresh install opens without What's New");
});

test("the new-player theme: Pale Blue and the Minecraft font", () => {
  assert.equal(document.documentElement.style.getPropertyValue("--acc").toLowerCase(), "#c0dcff");
  assert.equal(document.documentElement.dataset.font, "minecraft");
});

test("its menu: Lunar Client and Forge (Prism), each with the download mark when it isn't installed", async () => {
  hero().click();
  const menu = () => home.querySelector("#layer .menu");
  await waitFor(() => menu() != null, { label: "the menu" });
  assert.equal(menu().getAttribute("aria-label"), "Set up");
  assert.equal(hero().getAttribute("aria-expanded"), "true");
  const rows = [...menu().querySelectorAll(".mrow")];
  assert.deepEqual(rows.map((r) => r.textContent.trim()), ["Lunar Client", "Forge (Prism)"]);
  assert.deepEqual(rows.map((r) => r.dataset.act), ["get", "get"]);
  assert.deepEqual(rows.map((r) => r.getAttribute("aria-label")), ["Lunar Client, download", "Prism Launcher, download"]);
  assert.ok(rows.every((r) => r.querySelector(".ck svg")), "the download mark");
  document.activeElement.dispatchEvent(new window.KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  assert.equal(menu(), null, "Esc closes it");
});

test("picking a missing client opens its download page and waits: Install Lunar + Download", async () => {
  hero().click();
  await waitFor(() => home.querySelector('#layer .mrow[data-id="lunar"]') != null, { label: "the menu" });
  home.querySelector('#layer .mrow[data-id="lunar"]').click();
  await waitFor(() => callsOf("get_launcher").length === 1, { label: "Lunar's download page" });
  assert.deepEqual(callsOf("get_launcher")[0].args, { kind: "lunar" });
  await waitFor(() => under()?.classList.contains("wait"), { label: "the install row" });
  assert.ok(hero().classList.contains("setup"), "the same key stays");
  assert.equal(under().querySelector(".t").textContent, "Install Lunar");
  assert.equal(said(), "Waiting for Lunar Client to be installed. Setup starts by itself once it is.");
  under().querySelector('[data-act="download"]').click();
  await waitFor(() => callsOf("get_launcher").length === 2, { label: "the page again" });
  assert.deepEqual(callsOf("get_launcher")[1].args, { kind: "lunar" });
  assert.equal(callsOf("begin_setup").length, 0);
});

test("once Lunar is installed its setup starts by itself, and the column leaves the middle", async () => {
  status = LUNAR_INSTALLED;
  await waitFor(() => callsOf("begin_setup").length === 1, { label: "setup to start", timeout: 4000 });
  assert.deepEqual(callsOf("begin_setup")[0].args, { kind: "lunar" });
  await waitFor(() => $("#main .hero.slim") != null, { label: "the slim bar" });
  assert.equal($(".body.home.solo"), null);
  assert.equal($("#main .hero.slim").getAttribute("aria-label"), "Setting up Lunar");
  assert.equal($("#main .hero.slim").dataset.pct, "4");
  assert.equal(under().textContent.trim(), "Setting up Lunar");
  assert.equal(said(), "Checking Cobblify's own files.");
  assert.equal($(".caret"), null, "no menu during setup");
});

test("each check moves the bar; the check's name is read out", async () => {
  progress({ check: 1, phase: "running" });
  await waitFor(() => $("#main .hero.slim")?.dataset.pct === "17", { label: "check 2" });
  assert.equal(said(), "Looking for Lunar and Prism.");
});

test("the sign-in stop: Sign in to Lunar + Open at 33%; Open opens Lunar", async () => {
  progress({ check: 2, phase: "stop", stop: "signin" });
  await waitFor(() => $("#main .hero.slim")?.getAttribute("aria-label") === "Sign in to Lunar", { label: "the sign-in stop" });
  assert.equal($("#main .hero.slim").dataset.pct, "33");
  assert.equal(under().querySelector(".t").textContent, "Sign in to Lunar");
  assert.equal(said(), "Sign in to Lunar. Lunar hasn't been signed in on this computer yet.");
  const open = under().querySelector('[data-act="open-client"]');
  assert.equal(open.textContent.trim(), "Open");
  assert.ok(!open.classList.contains("acc"), "a grey key");
  open.click();
  await waitFor(() => callsOf("open_launcher").length === 1, { label: "Lunar opened" });
  assert.deepEqual(callsOf("open_launcher")[0].args, { kind: "lunar" });
});

test("settings' client rows are locked during setup", async () => {
  $('[data-act="settings"]').click();
  const sheet = el("sheet");
  await waitFor(() => sheet.classList.contains("open"), { label: "the sheet" });
  assert.equal(sheet.querySelector('[data-row="lunar"]'), null, "Repair only once Lunar is set up");
  assert.equal(sheet.querySelector('[data-row="prism"] .key').textContent.trim(), "Add");
  assert.equal(sheet.querySelector('[data-row="prism"] .key').disabled, true);
  sheet.querySelector('[data-act="close"]').click();
});

test("signed in: Closing Lunar at 50%", async () => {
  progress({ check: 3, phase: "stop", stop: "closing_lunar", label: "closing_lunar" });
  await waitFor(() => $("#main .hero.slim")?.getAttribute("aria-label") === "Closing Lunar", { label: "Closing Lunar" });
  assert.equal($("#main .hero.slim").dataset.pct, "50");
  assert.equal(said(), "Closing Lunar.");
  assert.equal(under().querySelector(".key"), null);
});

test("a game left open: Close Minecraft, a wait with nothing to press", async () => {
  progress({ check: 4, phase: "stop", stop: "game_open" });
  await waitFor(() => /Close Minecraft/.test(hero()?.textContent ?? ""), { label: "Close Minecraft" });
  assert.ok(hero().classList.contains("wait"));
  assert.equal(hero().getAttribute("aria-disabled"), "true");
  assert.equal(under(), null);
  assert.equal(said(), "Game open. Minecraft is running from Lunar. Setup finishes once it closes.");
  progress({ check: 4, phase: "running" });
  await waitFor(() => $("#main .hero.slim")?.dataset.pct === "67", { label: "setup again" });
  assert.equal(said(), "Looking for old Cobblify files.");
});

test("a failure: Try again resumes the setup at the check it failed at", async () => {
  progress({ check: 5, phase: "failed", failedAt: 6, message: "Cannot copy" });
  await waitFor(() => hero()?.textContent.trim() === "Try again", { label: "Try again" });
  assert.equal(said(), "Setup didn't finish. Play waits until setup finishes.");
  assert.equal(under(), null);
  hero().click();
  await waitFor(() => callsOf("resume_setup").length === 1, { label: "the resume" });
  assert.deepEqual(callsOf("resume_setup")[0].args, { kind: "lunar" });
  await waitFor(() => $("#main .hero.slim")?.dataset.pct === "83", { label: "the resumed check" });
  assert.equal(said(), "Adding Cobblify to Lunar.");
  // an event of the failed run arriving late changes nothing
  emit("setup://progress", { gen: gen - 1, kind: "lunar", check: 5, phase: "failed", failedAt: 6 });
  await tick(20);
  assert.ok($("#main .hero.slim"));
});

test("done: the bar holds full, then Launch arrives with the bed", async () => {
  status = LUNAR_SET_UP;
  const refreshes = callsOf("refresh_setup").length;
  progress({ check: 6, phase: "done" });
  await waitFor(() => $('#main [data-act="play"]') != null, { label: "Launch" });
  assert.ok(callsOf("refresh_setup").length > refreshes, "the status is read again");
  assert.equal($('#main [data-act="play"]').getAttribute("aria-label"), "Launch Cobblify in Lunar Client");
  assert.ok($(".caret"), "the client menu is back");
  assert.equal(said(), "In game, Right Shift opens Cobblify's settings.");
  assert.equal(window.localStorage.getItem("cobblify.client"), "lunar");
});
