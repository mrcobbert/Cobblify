import test from "node:test";
import assert from "node:assert/strict";

import { PREVIEW_SETUP_KEYS, PREVIEW_STATUS } from "./setup-fixtures.js";
import {
  ISSUE,
  dualConflict,
  dualConflictActions,
  launchLabel,
  setupActions,
  setupBlocks,
  setupHeading,
  topLevelError,
} from "./setup-view.js";

function renderedText(status) {
  const blocks = setupBlocks(status);
  const err = topLevelError(status);
  const parts = [];
  if (err) parts.push(err.heading, err.detail, ...err.actions.map((a) => a.label));
  for (const block of blocks) {
    parts.push(block.heading, block.hint, block.detailText);
    for (const c of block.candidates) {
      parts.push(c.name);
      if (c.action?.label) parts.push(c.action.label);
    }
    for (const a of block.actions) parts.push(a.label);
  }
  return parts.filter(Boolean).join("\n");
}

test("setup headings map issue codes, not message text", () => {
  assert.equal(setupHeading(PREVIEW_STATUS.lunarJars.targets[0], PREVIEW_STATUS.lunarJars), "Conflicting Jars");
  assert.equal(setupHeading(PREVIEW_STATUS.lunarBlocked.targets[0], PREVIEW_STATUS.lunarBlocked), "Quit Lunar");
  assert.equal(setupHeading(PREVIEW_STATUS.prismChoose.targets[1], PREVIEW_STATUS.prismChoose), "Choose Prism");
  assert.equal(
    setupHeading(PREVIEW_STATUS.prismNoCompatible.targets[1], PREVIEW_STATUS.prismNoCompatible),
    "No Compatible Prism Instance",
  );
  assert.equal(setupHeading(PREVIEW_STATUS.prismNoInstances.targets[1], PREVIEW_STATUS.prismNoInstances), "Get Prism");
  assert.equal(setupHeading(PREVIEW_STATUS.lunarAbsent.targets[0], PREVIEW_STATUS.lunarAbsent), "Get Lunar");
  assert.equal(
    setupHeading(PREVIEW_STATUS.forgeReadyRenamed.targets[1], PREVIEW_STATUS.forgeReadyRenamed),
    "Old Jar Disabled",
  );
});

test("running Lunar shows the platform guidance supplied by the backend", () => {
  const block = setupBlocks(PREVIEW_STATUS.lunarBlocked)[0];
  assert.equal(block.heading, "Quit Lunar");
  assert.equal(block.hint, "Quit Lunar completely with ⌘Q, then return to Cobblify.");
});

test("preview matrix exposes distinct actionable states", () => {
  const headings = new Map();
  for (const key of PREVIEW_SETUP_KEYS) {
    if (key === "bundleError") continue;
    const status = PREVIEW_STATUS[key];
    const text = renderedText(status);
    headings.set(key, text);
    if (key === "dualConflict") {
      assert.match(text, /Conflicting Jars/);
      assert.match(text, /Open Lunar Folder/);
      assert.match(text, /Open Prism Folder/);
      continue;
    }
    if (key === "prismChoose") {
      assert.match(text, /Choose Prism/);
      assert.match(text, /Hypixel-1.8.9/);
      assert.match(text, /Set Up/);
    }
    if (key === "bothReady") {
      assert.equal(setupBlocks(status).length, 0);
    }
  }
  assert.notEqual(headings.get("lunarBlocked"), headings.get("lunarUninitialized"));
  assert.notEqual(headings.get("prismNoInstances"), headings.get("prismNoCompatible"));
});

test("rendered setup never includes filesystem paths", () => {
  const blob = JSON.stringify(PREVIEW_STATUS);
  for (const key of PREVIEW_SETUP_KEYS) {
    const text = renderedText(PREVIEW_STATUS[key]);
    assert.doesNotMatch(text, /\/Users\//);
    assert.doesNotMatch(text, /AppData\\/);
    assert.doesNotMatch(text, /Library\/Application Support/);
    assert.doesNotMatch(text, /\\.weave\\/);
  }
  assert.doesNotMatch(blob, /"path"/);
  assert.doesNotMatch(blob, /"compat"/);
});

test("chooser rows only include eligible id/name candidates", () => {
  const choose = PREVIEW_STATUS.prismChoose.targets.find((t) => t.action === "choose");
  assert.deepEqual(choose.candidates, [
    { id: "d0", name: "Hypixel-1.8.9" },
    { id: "d1", name: "Skyblock-1.8.9" },
  ]);
  const block = setupBlocks(PREVIEW_STATUS.prismChoose).find((b) => b.heading === "Choose Prism");
  assert.equal(block.candidates.length, 2);
  assert.deepEqual(Object.keys(block.candidates[0]), ["id", "name", "action"]);
});

test("dual conflict collapses to one block with two folder actions", () => {
  assert.ok(dualConflict(PREVIEW_STATUS.dualConflict));
  const blocks = setupBlocks(PREVIEW_STATUS.dualConflict);
  assert.equal(blocks.length, 1);
  assert.deepEqual(
    blocks[0].actions.map((a) => a.label),
    dualConflictActions().map((a) => a.label),
  );
});

test("setup actions include refresh, open, download, and setup", () => {
  assert.deepEqual(
    setupActions(PREVIEW_STATUS.lunarUninitialized.targets[0], PREVIEW_STATUS.lunarUninitialized).map(
      (a) => a.kind,
    ),
    ["open_launcher"],
  );
  assert.deepEqual(
    setupActions(PREVIEW_STATUS.lunarAbsent.targets[0], PREVIEW_STATUS.lunarAbsent).map((a) => a.kind),
    ["download"],
  );
  assert.deepEqual(
    setupActions(PREVIEW_STATUS.prismNoInstances.targets[1], PREVIEW_STATUS.prismNoInstances).map(
      (a) => a.kind,
    ),
    ["download"],
  );
  assert.deepEqual(
    setupActions(PREVIEW_STATUS.prismNoCompatible.targets[1], PREVIEW_STATUS.prismNoCompatible).map(
      (a) => a.kind,
    ),
    ["open_launcher"],
  );
  assert.deepEqual(
    setupActions(PREVIEW_STATUS.lunarJars.targets[0], PREVIEW_STATUS.lunarJars).map((a) => a.kind),
    ["open_folder"],
  );
  assert.equal(topLevelError(PREVIEW_STATUS.forgeError), null);
  assert.deepEqual(
    setupBlocks(PREVIEW_STATUS.forgeError)
      .find((b) => b.heading === "Setup Failed")
      .actions.map((a) => a.kind),
    ["refresh"],
  );
});

test("target setup errors render once while bundle errors use the global block", () => {
  assert.equal(setupBlocks(PREVIEW_STATUS.lunarError).length, 2);
  assert.equal(
    setupBlocks(PREVIEW_STATUS.lunarError).filter((b) => b.heading === "Setup Failed").length,
    1,
  );
  assert.equal(topLevelError(PREVIEW_STATUS.lunarError), null);
  assert.equal(topLevelError(PREVIEW_STATUS.bundleError).heading, "Setup Failed");
});

test("launch labels use Prism branding", () => {
  assert.equal(launchLabel("lunar"), "Launch Lunar");
  assert.equal(launchLabel("forge"), "Launch Prism");
});

test("renamed jar details expose filenames only", () => {
  const block = setupBlocks(PREVIEW_STATUS.forgeReadyRenamed).find(
    (b) => b.heading === "Old Jar Disabled",
  );
  assert.match(block.detailText, /cobblify-disabled/);
  assert.doesNotMatch(block.detailText, /\//);
});

test("no compatible Prism state explains the required version behind details", () => {
  const block = setupBlocks(PREVIEW_STATUS.prismNoCompatible).find(
    (b) => b.heading === "No Compatible Prism Instance",
  );
  assert.equal(block.hasDetails, true);
  assert.equal(
    block.detailText,
    "Prism instances must use Minecraft 1.8.9 with Forge.",
  );
});

test("issue constants stay aligned with backend codes", () => {
  assert.equal(ISSUE.MISSING_LAUNCHER, "missing_launcher");
  assert.equal(ISSUE.NO_COMPATIBLE_PRISM, "no_compatible_prism_instance");
});
