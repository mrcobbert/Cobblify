import test from "node:test";
import assert from "node:assert/strict";

import { compareVersions, isValidVersion } from "../tools/semver-compare.mjs";

// The release tooling's ordering must agree with the Worker's and with the semver crate
// the launcher uses: dev builds sit between the previous stable and their own release.
test("pre-release identifiers order per semver §11.4", () => {
  assert.equal(compareVersions("0.14.1-dev.10", "0.14.1-dev.9"), 1);
  assert.equal(compareVersions("0.14.1-dev.9", "0.14.1-dev.10"), -1);
  assert.equal(compareVersions("0.14.1", "0.14.1-dev.10"), 1);
  assert.equal(compareVersions("0.14.1-dev.10", "0.14.0"), 1);
  assert.equal(compareVersions("0.14.1-alpha", "0.14.1-alpha.1"), -1);
  assert.equal(compareVersions("0.14.1-1", "0.14.1-a"), -1);
  assert.equal(compareVersions("0.14.1-dev.10", "0.14.1-dev.10"), 0);
});

test("numeric identifiers stay exact beyond Number's safe range, in both orders", () => {
  assert.equal(compareVersions("1.0.0-dev.9007199254740993", "1.0.0-dev.9007199254740992"), 1);
  assert.equal(compareVersions("1.0.0-dev.9007199254740992", "1.0.0-dev.9007199254740993"), -1);
  assert.equal(compareVersions("99999999999999999999.0.0", "9999999999999999999.0.0"), 1);
});

test("malformed versions are rejected rather than ordered", () => {
  assert.equal(compareVersions("0.14", "0.14.0"), null);
  assert.equal(compareVersions("0.14.0+build", "0.14.0"), null);
  assert.equal(isValidVersion("0.14.1-dev.41"), true);
  assert.equal(isValidVersion("v0.14.1"), false);
  assert.equal(isValidVersion("0.14.1-dev..41"), false);
  assert.equal(isValidVersion("0.14.1-dev.041"), false);
  assert.equal(isValidVersion("0.14.1-"), false);
  assert.equal(isValidVersion("0.14.1-dev.0"), true);
  assert.equal(isValidVersion("0.14.1-0a"), true);
});
