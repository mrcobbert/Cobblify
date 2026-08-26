import test from "node:test";
import assert from "node:assert/strict";

import { updateView } from "./update-view.js";
import {
  PREVIEW_UPDATE,
  PREVIEW_UPDATE_KEYS,
  PREVIEW_UPDATE_LABELS,
  assertBackendShapedUpdate,
  resolvePreviewUpdate,
} from "./update-fixtures.js";

test("every preview update fixture matches the backend snapshot shape", () => {
  for (const key of PREVIEW_UPDATE_KEYS) {
    assertBackendShapedUpdate(PREVIEW_UPDATE[key]);
  }
});

test("every preview-bar update key has a human label", () => {
  for (const key of PREVIEW_UPDATE_KEYS) {
    assert.ok(PREVIEW_UPDATE_LABELS[key], `missing label for ${key}`);
  }
});

test("unknown update keys fall back to first-run consent", () => {
  assert.equal(resolvePreviewUpdate("nope"), PREVIEW_UPDATE.consent);
  assert.equal(resolvePreviewUpdate(undefined), PREVIEW_UPDATE.consent);
});

test("preview update fixtures cover every distinct updater row", () => {
  const kinds = PREVIEW_UPDATE_KEYS.map((key) => updateView(PREVIEW_UPDATE[key]).kind);
  for (const kind of [
    "consent",
    "checking",
    "current",
    "available",
    "critical_required",
    "downloading",
    "paused",
    "ready",
    "installing",
    "error",
  ]) {
    assert.ok(kinds.includes(kind), `missing updater row kind: ${kind}`);
  }

  assert.equal(updateView(PREVIEW_UPDATE.current).secondaryAction, "enable");
  assert.equal(updateView(PREVIEW_UPDATE.currentAuto).secondaryAction, "disable");
  assert.equal(updateView(PREVIEW_UPDATE.paused).detail, "Progress saved.");
  assert.equal(updateView(PREVIEW_UPDATE.pausedGame).detail, "Resumes after game.");
  assert.equal(updateView(PREVIEW_UPDATE.critical).blocksLaunch, true);
  assert.equal(updateView(PREVIEW_UPDATE.updateReadyCritical).blocksLaunch, true);
  assert.equal(updateView(PREVIEW_UPDATE.installing).blocksLaunch, false);
  assert.equal(updateView(PREVIEW_UPDATE.updateError).title, "Unavailable");
  assert.equal(updateView(PREVIEW_UPDATE.updateErrorManual).title, "Check failed");
});

test("a demo fixture and a live backend snapshot of the same state project identically", () => {
  for (const key of PREVIEW_UPDATE_KEYS) {
    const fixture = PREVIEW_UPDATE[key];
    const live = {
      state: fixture.state,
      currentVersion: fixture.currentVersion,
      downloadedBytes: fixture.downloadedBytes,
      sizeBytes: fixture.sizeBytes,
      critical: fixture.critical,
      autoUpdateEnabled: fixture.autoUpdateEnabled,
      autoUpdatePrompted: fixture.autoUpdatePrompted,
      manual: fixture.manual,
    };
    if (fixture.availableVersion) live.availableVersion = fixture.availableVersion;
    if (fixture.pauseReason) live.pauseReason = fixture.pauseReason;
    if (fixture.diagnosticCode) live.diagnosticCode = fixture.diagnosticCode;
    assert.deepEqual(updateView(fixture), updateView(live), key);
  }
});
