#!/usr/bin/env node
import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

import { compareVersions, isValidVersion } from "./semver-compare.mjs";

const run = promisify(execFile);

const [output, version, minimumVersion, notes, releaseNotesUrl, macArtifact, macSignatureFile, winArtifact, winSignatureFile, policySignatureFile] = process.argv.slice(2);
if (![output, version, minimumVersion, notes, releaseNotesUrl, macArtifact, macSignatureFile, winArtifact, winSignatureFile, policySignatureFile].every(Boolean)) {
  throw new Error("missing update manifest argument");
}
if (!isValidVersion(version) || !isValidVersion(minimumVersion)) throw new Error("invalid release version");
if (compareVersions(minimumVersion, version) > 0) throw new Error("minimum supported version exceeds release version");

const artifact = async (file, signatureFile, key) => {
  const bytes = await readFile(file);
  const signature = (await readFile(signatureFile, "utf8")).trim();
  if (!signature) throw new Error(`empty signature for ${file}`);
  return { key, signature, sha256: createHash("sha256").update(bytes).digest("hex"), sizeBytes: (await stat(file)).size };
};

/**
 * Signs `file` with the release key the workflow step already exports
 * (TAURI_SIGNING_PRIVATE_KEY / TAURI_SIGNING_PRIVATE_KEY_PASSWORD) by running the same
 * `tauri signer sign` the step uses for the policy, and returns the `.sig` contents.
 */
async function signWithReleaseKey(file) {
  if (!process.env.TAURI_SIGNING_PRIVATE_KEY && !process.env.TAURI_SIGNING_PRIVATE_KEY_PATH) {
    throw new Error("TAURI_SIGNING_PRIVATE_KEY is not set: the release blob cannot be signed");
  }
  const cli = fileURLToPath(import.meta.resolve("@tauri-apps/cli/tauri.js"));
  try {
    await run(process.execPath, [cli, "signer", "sign", file]);
  } catch (error) {
    throw new Error(`tauri signer sign failed for ${file}: ${error.stderr || error.message}`);
  }
  const signature = (await readFile(`${file}.sig`, "utf8")).trim();
  if (!signature) throw new Error(`empty signature for ${file}`);
  await rm(`${file}.sig`, { force: true });
  return signature;
}

const policy = JSON.stringify({ minimumSupportedVersion: minimumVersion });
const manifest = {
  schema: 1,
  version,
  pubDate: new Date().toISOString(),
  notes,
  releaseNotesUrl,
  minimumSupportedVersion: minimumVersion,
  policy,
  policySignature: (await readFile(policySignatureFile, "utf8")).trim(),
  platforms: {},
};
const mac = await artifact(macArtifact, macSignatureFile, `releases/${version}/Cobblify-Launcher-macos-universal.app.tar.gz`);
manifest.platforms["darwin-universal"] = mac;
manifest.platforms["darwin-aarch64"] = mac;
manifest.platforms["darwin-x86_64"] = mac;
manifest.platforms["windows-x86_64"] = await artifact(winArtifact, winSignatureFile, `releases/${version}/Cobblify-Launcher-windows-x86_64-setup.exe`);

// The signed release blob (launcher L4). Everything a launcher acts on - version, the
// minimum the policy enforces, and each platform's key/sha256/size - travels inside one
// signed string, so the bucket cannot offer an older signed bundle under a newer version or
// change the hash a download is checked against. The unsigned copies above stay exactly as
// they were for launchers older than 0.16.0, which read only those.
const release = JSON.stringify({
  schema: 1,
  version,
  minimumSupportedVersion: minimumVersion,
  platforms: Object.fromEntries(
    Object.entries(manifest.platforms).map(([target, { key, sha256, sizeBytes }]) => [target, { key, sha256, sizeBytes }]),
  ),
});
const releaseFile = path.join(path.dirname(path.resolve(output)), `${path.basename(output)}.release.json`);
await writeFile(releaseFile, release, { flag: "wx" });
try {
  manifest.release = release;
  manifest.releaseSignature = await signWithReleaseKey(releaseFile);
} finally {
  await rm(releaseFile, { force: true });
}
await writeFile(output, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx" });
console.log(`built signed update manifest ${output} for ${version}`);
