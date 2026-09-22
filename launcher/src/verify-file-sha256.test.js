import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { verifyFileSha256 } from "../tools/verify-file-sha256.mjs";

const TOOL = fileURLToPath(new URL("../tools/verify-file-sha256.mjs", import.meta.url));
const EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

test("accepts a file matching the pinned SHA-256", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-sha-"));
  try {
    const file = path.join(dir, "agent.jar");
    await writeFile(file, "");
    await verifyFileSha256(file, EMPTY_SHA256);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("rejects changed bytes and malformed expected hashes", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-sha-"));
  try {
    const file = path.join(dir, "agent.jar");
    await writeFile(file, "changed");
    await assert.rejects(verifyFileSha256(file, EMPTY_SHA256), /SHA-256 mismatch/);
    await assert.rejects(verifyFileSha256(file, "not-a-hash"), /64 lowercase/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

// The CLI half runs only when this file is the entry point, and "the guard said no" is
// indistinguishable from success from the outside: exit 0, nothing printed, nothing
// verified. A release step that calls the tool through a symlink or from a path with a
// space in it must still verify.
test("the CLI verifies through a symlink and from a path containing a space", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-sha-cli-"));
  try {
    const file = path.join(dir, "agent.jar");
    await writeFile(file, "");

    const link = path.join(dir, "verify-through-link.mjs");
    await symlink(TOOL, link);
    const mismatch = spawnSync(process.execPath, [link, file, "0".repeat(64)], { encoding: "utf8" });
    assert.equal(mismatch.status, 1);
    assert.match(mismatch.stderr, /SHA-256 mismatch/);

    const spaced = path.join(dir, "dir with space");
    await mkdir(spaced);
    const copy = path.join(spaced, "verify-file-sha256.mjs");
    await copyFile(TOOL, copy);
    const verified = spawnSync(process.execPath, [copy, file, EMPTY_SHA256], { encoding: "utf8" });
    assert.equal(verified.status, 0);
    assert.match(verified.stdout, /SHA-256 verified/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
