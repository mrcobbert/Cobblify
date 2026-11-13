import test from "node:test";
import assert from "node:assert/strict";

import {
  PREVIEW_SETUP_KEYS,
  PREVIEW_SETUP_LABELS,
  PREVIEW_STATUS,
  PREVIEW_STATUS_ALIASES,
  aggregateStatus,
  assertBackendShapedStatus,
  assertPrismCandidate,
  collectPreviewCandidates,
  resolvePreviewStatus,
} from "./setup-fixtures.js";
import { ISSUE } from "./setup-view.js";

test("every preview setup fixture matches the backend status shape", () => {
  for (const key of PREVIEW_SETUP_KEYS) {
    assertBackendShapedStatus(PREVIEW_STATUS[key]);
  }
});

test("preview aliases resolve to backend-shaped fixtures", () => {
  for (const [alias, canonical] of Object.entries(PREVIEW_STATUS_ALIASES)) {
    assert.equal(resolvePreviewStatus(alias), PREVIEW_STATUS[canonical]);
  }
});

test("preview fixtures carry no Prism candidates (no instance pick)", () => {
  for (const c of collectPreviewCandidates()) assertPrismCandidate(c);
  assert.equal(collectPreviewCandidates().length, 0);
});

test("preview fixtures never reference removed launcher paths", () => {
  const blob = JSON.stringify(PREVIEW_STATUS);
  assert.doesNotMatch(blob, /CurseForge/i);
  assert.doesNotMatch(blob, /Chosen folder/i);
  assert.doesNotMatch(blob, /curseforge/i);
  assert.doesNotMatch(blob, /\/Users\//);
  assert.doesNotMatch(blob, /"path"/);
  assert.doesNotMatch(blob, /"compat"/);
  assert.doesNotMatch(blob, /incompatible/);
  assert.doesNotMatch(blob, /Unreadable-Pack/);
});

test("every preview-bar setup key has a human label", () => {
  for (const key of PREVIEW_SETUP_KEYS) {
    assert.ok(PREVIEW_SETUP_LABELS[key], `missing label for ${key}`);
  }
});

test("prism setup ships no instance pick: Cobblify makes its own instance", () => {
  const setup = PREVIEW_STATUS.prismSetup.targets.find((t) => t.kind === "forge");
  assert.equal(setup.action, "none");
  assert.equal(setup.candidates.length, 0);
  assert.equal(setup.issue, null);
});

test("prism missing vs needing a new setup use distinct issue codes", () => {
  const missing = PREVIEW_STATUS.prismNoInstances.targets.find((t) => t.kind === "forge");
  const renew = PREVIEW_STATUS.prismNewSetup.targets.find((t) => t.kind === "forge");
  assert.equal(missing.issue, ISSUE.MISSING_LAUNCHER);
  assert.equal(renew.issue, ISSUE.PRISM_NEW_SETUP);
  assert.equal(renew.set_up, true);
  assert.equal(renew.message, "Prism needs a new setup.");
  assert.equal(missing.candidates.length, 0);
  assert.equal(renew.candidates.length, 0);
});

test("no fixture speaks of Forge any more", () => {
  assert.doesNotMatch(JSON.stringify(PREVIEW_STATUS), /Forge/);
});

test("aggregated fixtures match backend aggregation semantics", () => {
  for (const key of PREVIEW_SETUP_KEYS) {
    if (key === "bundleError") continue;
    const fixture = PREVIEW_STATUS[key];
    const agg = aggregateStatus(fixture.mod_version, fixture.targets);
    assert.equal(fixture.state, agg.state, `${key}: state`);
    assert.equal(fixture.message, agg.message, `${key}: message`);
  }
});

test("distinct preview states cover lunar running and uninitialized", () => {
  assert.equal(
    PREVIEW_STATUS.lunarBlocked.targets[0].issue,
    ISSUE.RUNNING_LUNAR,
  );
  assert.equal(
    PREVIEW_STATUS.lunarUninitialized.targets[0].issue,
    ISSUE.UNINITIALIZED_LUNAR,
  );
  assert.notEqual(
    PREVIEW_STATUS.lunarBlocked.targets[0].message,
    PREVIEW_STATUS.lunarUninitialized.targets[0].message,
  );
});
