import test from "node:test";
import assert from "node:assert/strict";

import { blocksLaunch, sheetModel } from "./ui/model.js";

/** The settings sheet's version row for an updater snapshot (the 0.18 home has no updater strip). */
const row = (u) => sheetModel({ status: { state: "ready", targets: [] }, update: u }).version;
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

test("preview update fixtures cover every distinct updater state", () => {
  const states = PREVIEW_UPDATE_KEYS.map((key) => PREVIEW_UPDATE[key].state);
  for (const state of ["checking", "current", "available", "critical_required", "downloading", "paused", "ready", "installing", "error"]) {
    assert.ok(states.includes(state), `missing updater state: ${state}`);
  }

  assert.equal(row(PREVIEW_UPDATE.current).word, "Up to date");
  assert.equal(row(PREVIEW_UPDATE.current).key.action, "check");
  assert.equal(row(PREVIEW_UPDATE.paused).word, "Paused");
  assert.equal(row(PREVIEW_UPDATE.paused).key.action, "resume");
  assert.equal(blocksLaunch(PREVIEW_UPDATE.critical), true);
  assert.equal(blocksLaunch(PREVIEW_UPDATE.updateReadyCritical), true);
  assert.equal(blocksLaunch(PREVIEW_UPDATE.installing), false);
  assert.equal(row(PREVIEW_UPDATE.updateErrorManual).word, "Check failed");
  assert.equal(row(PREVIEW_UPDATE.availableDev).word, "0.10.0-dev.41");
  assert.equal(row(PREVIEW_UPDATE.availableDev).key.action, "download");
  assert.equal(row(PREVIEW_UPDATE.updateReady).key.action, "install");
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
      updateChannel: fixture.updateChannel,
      channelLoaded: fixture.channelLoaded,
      channelSaving: fixture.channelSaving,
      manual: fixture.manual,
    };
    if (fixture.availableVersion) live.availableVersion = fixture.availableVersion;
    if (fixture.pauseReason) live.pauseReason = fixture.pauseReason;
    if (fixture.diagnosticCode) live.diagnosticCode = fixture.diagnosticCode;
    assert.deepEqual(row(fixture), row(live), key);
    assert.equal(blocksLaunch(fixture), blocksLaunch(live), key);
  }
});
