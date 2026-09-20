#!/usr/bin/env node
import { createHash } from "node:crypto";
import { readFile, stat, writeFile } from "node:fs/promises";

import { compareVersions, isValidVersion } from "./semver-compare.mjs";

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
await writeFile(output, `${JSON.stringify(manifest, null, 2)}\n`, { flag: "wx" });
console.log(`built signed update manifest ${output} for ${version}`);
