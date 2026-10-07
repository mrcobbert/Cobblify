import test from "node:test";
import assert from "node:assert/strict";

import { bootWindow } from "./test-support/window-harness.js";

/** A brand-new player: no Lunar, no Prism, no game config, a fresh install. */

const NOTHING = {
  state: "blocked",
  mod_version: "0.17.0",
  targets: [
    { kind: "lunar", state: "absent", issue: "missing_launcher", message: "Lunar Client is not installed." },
    { kind: "forge", state: "absent", issue: "missing_launcher", message: "Prism is not installed." },
  ],
};
const replies = {
  refresh_setup: () => NOTHING,
  open_info: () => ({ version: "0.18.0-next.1", updated: false, fresh: true }),
  game_theme: () => ({}),
  launch_preferences: () => ({ autoJoinHypixel: true, useExternalOverlay: true, health: "missing" }),
  update_preferences: () => ({ autoUpdateEnabled: true, autoUpdatePrompted: false, updateChannel: "stable", health: "missing" }),
  update_status: () => ({ state: "current", currentVersion: "0.18.0-next.1" }),
  check_for_update: () => ({ state: "current", currentVersion: "0.18.0-next.1" }),
  get_launcher: ({ kind }) => ({ installed: false, download_url: kind }),
};

const app = await bootWindow({ replies });
const { el, waitFor, tick, calls, document } = app;
const home = el("app");
const $ = (s) => home.querySelector(s);

await waitFor(() => $("#main .heros .hero")?.textContent.trim() === "Get Lunar", { label: "the interim setup key" });

test("the key stands alone, with no island, no menu and no What's New", async () => {
  assert.ok($(".body.home.solo"), "the solo layout");
  assert.equal($(".caret"), null);
  assert.equal($("#isl").style.display, "none", "no island before a client is set up");
  await tick(50);
  assert.equal($(".modal"), null, "a fresh install opens without What's New");
  assert.deepEqual([...home.querySelectorAll("#main .keys2 .key")].map((k) => k.textContent.trim()), ["Get Prism"]);
});

test("the new-player theme: Pale Blue and the Minecraft font", () => {
  assert.equal(document.documentElement.style.getPropertyValue("--acc").toLowerCase(), "#c0dcff");
  assert.equal(document.documentElement.dataset.font, "minecraft");
});

test("Get Lunar and Get Prism open their download pages", async () => {
  $("#main .heros .hero").click();
  await waitFor(() => calls.some((c) => c.command === "get_launcher"), { label: "Lunar's download" });
  await tick(10); // one setup action at a time: the next click waits for this one to finish
  $("#main .keys2 .key").click();
  await waitFor(() => calls.filter((c) => c.command === "get_launcher").length === 2, { label: "both downloads" });
  assert.deepEqual(calls.filter((c) => c.command === "get_launcher").map((c) => c.args.kind), ["lunar", "forge"]);
});
