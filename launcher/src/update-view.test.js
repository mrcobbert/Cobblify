import test from "node:test";
import assert from "node:assert/strict";

import { updateView } from "./update-view.js";

const base = {
  state: "current",
  currentVersion: "0.9.1",
  autoUpdateEnabled: false,
  autoUpdatePrompted: true,
};

test("first-run consent stays inline and never blocks launching", () => {
  const view = updateView({ ...base, autoUpdatePrompted: false });
  assert.equal(view.kind, "consent");
  assert.equal(view.title, "Stay current?");
  assert.equal(view.detail, "Quiet updates.");
  assert.equal(view.primaryAction, "enable");
  assert.equal(view.primaryLabel, "Enable");
  assert.equal(view.secondaryAction, "dismiss_consent");
  assert.equal(view.blocksLaunch, false);
});

test("available, downloading, paused, and ready expose one honest primary action", () => {
  assert.equal(updateView({ ...base, state: "available", availableVersion: "0.10.0" }).primaryAction, "download");
  assert.equal(updateView({ ...base, state: "downloading", downloadedBytes: 25, sizeBytes: 100 }).primaryAction, "pause");
  assert.equal(updateView({ ...base, state: "paused", downloadedBytes: 25, sizeBytes: 100 }).primaryAction, "resume");
  assert.equal(updateView({ ...base, state: "ready", availableVersion: "0.10.0" }).primaryAction, "install");
});

test("only a verified critical requirement blocks a new launch", () => {
  const critical = updateView({
    ...base,
    state: "critical_required",
    availableVersion: "0.10.0",
    critical: true,
  });
  assert.equal(critical.blocksLaunch, true);
  assert.equal(critical.primaryAction, "download");
  assert.equal(updateView({ ...base, state: "ready", critical: true }).blocksLaunch, true);
  assert.equal(updateView({ ...base, autoUpdatePrompted: false, state: "critical_required", critical: true }).kind, "critical_required");

  const stale = updateView({ ...base, state: "error", diagnosticCode: "check_failed" });
  assert.equal(stale.blocksLaunch, false);
});

test("automatic failures stay quiet while manual failures offer retry", () => {
  const automatic = updateView({ ...base, state: "error", diagnosticCode: "check_failed" });
  const manual = updateView({ ...base, state: "error", diagnosticCode: "check_failed", manual: true });
  assert.equal(automatic.title, "Unavailable");
  assert.equal(manual.title, "Check failed");
  assert.equal(automatic.primaryAction, "check");
  assert.equal(manual.primaryAction, "check");
});

test("the ordinary row keeps auto-update choice reversible", () => {
  assert.equal(updateView({ ...base, state: "current", autoUpdateEnabled: false }).secondaryAction, "enable");
  assert.equal(updateView({ ...base, state: "current", autoUpdateEnabled: true }).secondaryAction, "disable");
});

test("release notes never change the compact row", () => {
  const states = [
    { state: "available", availableVersion: "0.10.0" },
    { state: "critical_required", availableVersion: "0.10.0", critical: true },
    { state: "ready", availableVersion: "0.10.0" },
  ];
  for (const extra of states) {
    const live = updateView({ ...base, ...extra });
    const noted = updateView({ ...base, ...extra, notes: "A long signed changelog from the channel." });
    assert.deepEqual(noted, live, extra.state);
  }
});
