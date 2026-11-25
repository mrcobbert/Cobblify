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
  assert.equal(setupHeading(PREVIEW_STATUS.prismSetup.targets[1], PREVIEW_STATUS.prismSetup), "Setup");
  assert.equal(
    setupHeading(PREVIEW_STATUS.prismNewSetup.targets[1], PREVIEW_STATUS.prismNewSetup),
    "Prism Needs a New Setup",
  );
  assert.equal(setupHeading(PREVIEW_STATUS.noPrismBuild.targets[1], PREVIEW_STATUS.noPrismBuild), "Prism Unavailable");
  assert.equal(setupHeading(PREVIEW_STATUS.prismNoInstances.targets[1], PREVIEW_STATUS.prismNoInstances), "Get Prism");
  assert.equal(setupHeading(PREVIEW_STATUS.lunarAbsent.targets[0], PREVIEW_STATUS.lunarAbsent), "Get Lunar");
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
    if (key === "prismNewSetup") {
      assert.match(text, /Prism Needs a New Setup/);
      assert.match(text, /Set Up/);
    }
    assert.doesNotMatch(text, /Forge/);
    if (key === "bothReady") {
      assert.equal(setupBlocks(status).length, 0);
    }
  }
  assert.notEqual(headings.get("lunarBlocked"), headings.get("lunarUninitialized"));
  assert.notEqual(headings.get("prismNoInstances"), headings.get("prismNewSetup"));
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

test("no Prism state lists instances to choose from", () => {
  for (const key of PREVIEW_SETUP_KEYS) {
    for (const block of setupBlocks(PREVIEW_STATUS[key])) assert.equal(block.candidates.length, 0, key);
  }
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
    setupActions(PREVIEW_STATUS.prismNewSetup.targets[1], PREVIEW_STATUS.prismNewSetup).map(
      (a) => a.kind,
    ),
    ["setup_prism"],
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

test("a ready target never shows a setup block", () => {
  assert.deepEqual(setupBlocks(PREVIEW_STATUS.bothReady), []);
});

test("issue constants stay aligned with backend codes", () => {
  assert.equal(ISSUE.MISSING_LAUNCHER, "missing_launcher");
  assert.equal(ISSUE.PRISM_NEW_SETUP, "prism_new_setup");
  assert.equal(ISSUE.MISSING_BUNDLED_FORGE, "missing_bundled_forge_jar");
});
