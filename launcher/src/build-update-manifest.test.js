import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const run = promisify(execFile);
const TOOL = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../tools/build-update-manifest.mjs");

// The tool is a release step, so it is exercised end to end: fake artifacts, real output file.
async function build(dir, version, minimum) {
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
  ]);
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
