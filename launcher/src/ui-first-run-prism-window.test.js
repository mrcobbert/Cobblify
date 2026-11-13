import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * A first run with both clients installed, in the real window: Choose Your Setup's rows are plain; Prism starts its
 * setup at once - Cobblify makes its own Ornithe instance, so there is no instance pick (an older backend's
 * `instances` are ignored) - and the column slides to its place. Prism's own sign-in stop opens Prism. One boot per
 * file; the tests run in order.
 */

const INSTANCES = [
  { id: "pvp", name: "PvP Pack", detail: "1.8.9", last_launch_ms: 300 },
  { id: "hyp", name: "Hypixel 1.8.9", detail: "1.8.9", last_launch_ms: 200 },
];
const STATUS = {
  state: "blocked",
  message: "",
  mod_version: "0.17.0",
  targets: [
    { kind: "lunar", state: "absent", issue: "not_set_up", message: "", action: "none", candidates: [], has_setup_folder: false, installed: true, set_up: false, opened_once: false },
    { kind: "forge", state: "absent", message: "Set up Prism.", action: "none", candidates: [], has_setup_folder: false, installed: true, set_up: false, instances: INSTANCES },
  ],
};

let gen = 0;
const replies = {
  refresh_setup: () => STATUS,
  setup_state: () => null,
  open_info: () => ({ version: "0.18.0-next.2", updated: true, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "missing" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  open_launcher: () => null,
  begin_setup: ({ kind }) => ({ gen: ++gen, kind, check: 0, phase: "running" }),
};

const app = await bootWindow({ replies });
const { el, waitFor, tick, calls, emit } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);
const hero = () => $("#main .heros .hero");
const said = () => $("#main .sr")?.textContent ?? "";
const callsOf = (cmd) => calls.filter((c) => c.command === cmd);

await waitFor(() => hero()?.classList.contains("setup"), { label: "Choose Your Setup" });

test("both installed: plain rows, and no What's New on a first run after an update", async () => {
  assert.equal(said(), "Choose a client: Lunar Client and Prism Launcher are both on this computer.");
  hero().click();
  await waitFor(() => home.querySelectorAll("#layer .mrow").length === 2, { label: "the menu" });
  const rows = [...home.querySelectorAll("#layer .mrow")];
  assert.deepEqual(rows.map((r) => r.dataset.act), ["setup-now", "setup-now"]);
  assert.ok(rows.every((r) => !r.hasAttribute("aria-label") && !r.querySelector(".ck svg")), "no download mark");
  await tick(30);
  assert.equal($(".modal"), null);
});

test("Prism: setup begins at once, with no instance pick, and the column leaves the middle", async () => {
  home.querySelector('#layer .mrow[data-id="prism"]').click();
  await waitFor(() => callsOf("begin_setup").length === 1, { label: "Prism's setup" });
  assert.deepEqual(callsOf("begin_setup")[0].args, { kind: "forge" });
  assert.equal($('#main [role="radiogroup"]'), null, "no pick");
  await waitFor(() => $("#main .hero.slim") != null, { label: "the slim bar" });
  assert.equal($(".body.home.solo"), null);
  assert.equal($("#main .hero.slim").getAttribute("aria-label"), "Setting up Prism");
});

test("Prism's sign-in stop: Sign in to Prism + Open, which opens Prism", async () => {
  emit("setup://progress", { gen, kind: "forge", check: 2, phase: "stop", stop: "signin" });
  await waitFor(() => $("#main .hero.slim")?.getAttribute("aria-label") === "Sign in to Prism", { label: "the stop" });
  assert.equal(said(), "Sign in to Prism. Prism has no Microsoft account on this computer yet.");
  $('#main .under [data-act="open-client"]').click();
  await waitFor(() => callsOf("open_launcher").length === 1, { label: "Prism opened" });
  assert.deepEqual(callsOf("open_launcher")[0].args, { kind: "forge" });
});
