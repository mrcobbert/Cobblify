import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

import { verifyMinisign } from "./test-support/minisign-verify.js";

const run = promisify(execFile);
const here = path.dirname(fileURLToPath(import.meta.url));
const TOOL = path.resolve(here, "../tools/build-update-manifest.mjs");
const TAURI = path.resolve(here, "../node_modules/@tauri-apps/cli/tauri.js");

// A throwaway release key per test file: the tool signs the release blob itself with the key
// the workflow step exports, so the test exports one the same way.
const keyDir = await mkdtemp(path.join(os.tmpdir(), "cobblify-manifest-key-"));
await run(process.execPath, [TAURI, "signer", "generate", "-w", path.join(keyDir, "key"), "-p", "test", "--ci"]);
const signingEnv = {
  ...process.env,
  TAURI_SIGNING_PRIVATE_KEY: await readFile(path.join(keyDir, "key"), "utf8"),
  TAURI_SIGNING_PRIVATE_KEY_PASSWORD: "test",
};
const publicKey = await readFile(path.join(keyDir, "key.pub"), "utf8");
test.after(() => rm(keyDir, { recursive: true, force: true }));

// The tool is a release step, so it is exercised end to end: fake artifacts, real output file.
async function build(dir, version, minimum, env = signingEnv) {
  const mac = path.join(dir, "mac.app.tar.gz");
  const win = path.join(dir, "win-setup.exe");
  await writeFile(mac, "mac-bytes");
  await writeFile(`${mac}.sig`, "mac-sig\n");
  await writeFile(win, "win-bytes");
  await writeFile(`${win}.sig`, "win-sig\n");
  await writeFile(path.join(dir, "policy.json.sig"), "policy-sig\n");
  const out = path.join(dir, `${version}.json`);
  await run(process.execPath, [
    TOOL, out, version, minimum, "notes", "https://example.invalid/notes",
    mac, `${mac}.sig`, win, `${win}.sig`, path.join(dir, "policy.json.sig"),
  ], { env });
  return JSON.parse(await readFile(out, "utf8"));
}

test("a dev pre-release manifest builds with the 0.0.0 policy floor", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-manifest-"));
  try {
    const manifest = await build(dir, "0.14.1-dev.41", "0.0.0");
    assert.equal(manifest.version, "0.14.1-dev.41");
    assert.equal(manifest.minimumSupportedVersion, "0.0.0");
    assert.equal(manifest.platforms["windows-x86_64"].key, "releases/0.14.1-dev.41/Cobblify-Launcher-windows-x86_64-setup.exe");
    assert.equal(manifest.platforms["darwin-universal"].key, "releases/0.14.1-dev.41/Cobblify-Launcher-macos-universal.app.tar.gz");
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("a minimum above the release is refused, with numeric pre-release order", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-manifest-"));
  try {
    // 0.14.1 (release) > 0.14.1-dev.41 (pre-release of it).
    await assert.rejects(build(dir, "0.14.1-dev.41", "0.14.1"), /minimum supported version exceeds/);
    // dev.9 < dev.10 numerically, so dev.10 as the floor for a dev.9 release is refused ...
    await assert.rejects(build(dir, "0.14.1-dev.9", "0.14.1-dev.10"), /minimum supported version exceeds/);
    // ... and the other way round is accepted.
    const ok = await build(dir, "0.14.1-dev.10", "0.14.1-dev.9");
    assert.equal(ok.version, "0.14.1-dev.10");
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

// L4: the release blob carries everything a launcher acts on, signed with the release key.
test("the manifest carries a signed release blob that verifies under the release key", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-manifest-"));
  try {
    const manifest = await build(dir, "0.16.0", "0.9.1");
    assert.equal(typeof manifest.release, "string");
    assert.equal(typeof manifest.releaseSignature, "string");
    const release = JSON.parse(manifest.release);
    assert.equal(release.schema, 1);
    assert.equal(release.version, "0.16.0");
    assert.equal(release.minimumSupportedVersion, "0.9.1");
    assert.deepEqual(Object.keys(release.platforms).sort(), ["darwin-aarch64", "darwin-universal", "darwin-x86_64", "windows-x86_64"]);
    for (const [target, entry] of Object.entries(release.platforms)) {
      const unsigned = manifest.platforms[target];
      assert.deepEqual(entry, { key: unsigned.key, sha256: unsigned.sha256, sizeBytes: unsigned.sizeBytes }, target);
      assert.match(entry.sha256, /^[a-f0-9]{64}$/);
    }
    const check = verifyMinisign(publicKey, manifest.releaseSignature, manifest.release);
    assert.deepEqual(check, { keyIdMatches: true, signatureValid: true, globalValid: true, prehashed: true });
    // The signature is over exactly the string in the manifest: any byte change breaks it.
    const tampered = verifyMinisign(publicKey, manifest.releaseSignature, manifest.release.replace("0.16.0", "0.16.1"));
    assert.equal(tampered.signatureValid, false);
    // No scratch files are left beside the manifest for the workflow's `find` to trip on.
    assert.deepEqual((await readdir(dir)).filter((f) => f.includes("release")), []);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("the fields launchers before 0.16.0 read are exactly what they were", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-manifest-"));
  try {
    const manifest = await build(dir, "0.16.0", "0.9.1");
    const { release, releaseSignature, pubDate, ...legacy } = manifest;
    assert.ok(release && releaseSignature && pubDate);
    const mac = {
      key: "releases/0.16.0/Cobblify-Launcher-macos-universal.app.tar.gz",
      signature: "mac-sig",
      sha256: sha256Hex("mac-bytes"),
      sizeBytes: 9,
    };
    assert.deepEqual(legacy, {
      schema: 1,
      version: "0.16.0",
      notes: "notes",
      releaseNotesUrl: "https://example.invalid/notes",
      minimumSupportedVersion: "0.9.1",
      policy: JSON.stringify({ minimumSupportedVersion: "0.9.1" }),
      policySignature: "policy-sig",
      platforms: {
        "darwin-universal": mac,
        "darwin-aarch64": mac,
        "darwin-x86_64": mac,
        "windows-x86_64": {
          key: "releases/0.16.0/Cobblify-Launcher-windows-x86_64-setup.exe",
          signature: "win-sig",
          sha256: sha256Hex("win-bytes"),
          sizeBytes: 9,
        },
      },
    });
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("without the signing key in the environment the tool refuses instead of emitting an unsigned blob", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-manifest-"));
  try {
    const env = { ...process.env };
    delete env.TAURI_SIGNING_PRIVATE_KEY;
    delete env.TAURI_SIGNING_PRIVATE_KEY_PATH;
    delete env.TAURI_SIGNING_PRIVATE_KEY_PASSWORD;
    await assert.rejects(build(dir, "0.16.0", "0.9.1", env), /TAURI_SIGNING_PRIVATE_KEY is not set/);
    assert.deepEqual((await readdir(dir)).filter((f) => f.endsWith(".json")), []);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

function sha256Hex(text) {
  return createHash("sha256").update(text).digest("hex");
}
