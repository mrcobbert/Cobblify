import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/**
 * The 0.18 home in the real window when Cobblify's files in Lunar need updating (the first open after an update)
 * and Lunar is open, so they can't be written yet: Repair, never a failure. Repair runs Lunar's setup, whose
 * "Closing Lunar" quits Lunar politely and goes on. One boot per file; the tests run in order.
 */

const LUNAR_OPEN = {
  state: "blocked",
  targets: [
    { kind: "lunar", state: "blocked", issue: "running_lunar", installed: true, set_up: true },
    { kind: "forge", state: "absent", issue: "missing_launcher", installed: false, set_up: false, instances: [] },
  ],
};

let gen = 0;
const replies = {
  refresh_setup: () => LUNAR_OPEN,
  status: () => LUNAR_OPEN,
  setup_state: () => null,
  open_info: () => ({ version: "0.18.0-next.2", updated: false, fresh: false }),
  game_theme: () => ({}),
  launch_preferences: () => ({ useExternalOverlay: true, health: "valid" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: false, updateChannel: "stable", health: "missing" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.2" }),
  begin_setup: ({ kind }) => ({ gen: ++gen, kind, check: 0, phase: "running" }),
};

const app = await bootWindow({ replies });
const { el, waitFor, calls, emit } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);
const hero = () => $("#main .heros .hero");
const said = () => $("#main .sr")?.textContent ?? "";
const callsOf = (cmd) => calls.filter((c) => c.command === cmd);

await waitFor(() => $('#main [data-act="repair-setup"]') != null, { label: "the Repair home" });

test("files to update with Lunar open: Repair, and nothing says setup failed", () => {
  assert.equal(hero().textContent.trim(), "Repair");
  assert.equal(said(), "Cobblify's files in Lunar need updating.");
  assert.doesNotMatch(home.textContent, /didn't finish|⌘Q/);
});

test("Repair runs Lunar's setup, which closes Lunar itself", async () => {
  hero().click();
  await waitFor(() => callsOf("begin_setup").length === 1, { label: "Lunar's setup" });
  assert.deepEqual(callsOf("begin_setup")[0].args, { kind: "lunar" });
  emit("setup://progress", { gen, kind: "lunar", check: 3, phase: "stop", stop: "closing_lunar", label: "closing_lunar" });
  await waitFor(() => $("#main .hero.slim")?.getAttribute("aria-label") === "Closing Lunar", { label: "Closing Lunar" });
  assert.equal(said(), "Closing Lunar.");
});
