import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

import { verifyFileSha256 } from "../tools/verify-file-sha256.mjs";

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
