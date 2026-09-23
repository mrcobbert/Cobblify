import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, rm, symlink } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { compareVersions, isValidVersion } from "../tools/semver-compare.mjs";

const TOOL = fileURLToPath(new URL("../tools/semver-compare.mjs", import.meta.url));

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

// The dev and stable channel guards call this tool by path from a workflow step and read
// its stdout: an ordering that prints nothing reads as "not newer" and refuses a perfectly
// good release. A symlinked or space-containing invocation must still print.
test("the CLI prints an ordering through a symlink and from a path containing a space", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-semver-cli-"));
  try {
    const link = path.join(dir, "compare-through-link.mjs");
    await symlink(TOOL, link);
    const run = (tool, a, b) => spawnSync(process.execPath, [tool, a, b], { encoding: "utf8" });

    const newer = run(link, "0.16.0", "0.15.1");
    assert.equal(newer.status, 0);
    assert.equal(newer.stdout.trim(), "1");
    assert.equal(run(link, "0.15.1", "0.16.0").stdout.trim(), "-1");
    assert.equal(run(link, "0.16.0", "0.16.0").stdout.trim(), "0");

    const spaced = path.join(dir, "dir with space");
    await mkdir(spaced);
    const copy = path.join(spaced, "semver-compare.mjs");
    await copyFile(TOOL, copy);
    const spacedRun = run(copy, "0.16.0", "0.15.1");
    assert.equal(spacedRun.status, 0);
    assert.equal(spacedRun.stdout.trim(), "1");
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
