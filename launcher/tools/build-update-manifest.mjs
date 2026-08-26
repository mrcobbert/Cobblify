#!/usr/bin/env node
import { createHash } from "node:crypto";
import { readFile, stat, writeFile } from "node:fs/promises";

const [output, version, minimumVersion, notes, releaseNotesUrl, macArtifact, macSignatureFile, winArtifact, winSignatureFile, policySignatureFile] = process.argv.slice(2);
if (![output, version, minimumVersion, notes, releaseNotesUrl, macArtifact, macSignatureFile, winArtifact, winSignatureFile, policySignatureFile].every(Boolean)) {
  throw new Error("missing update manifest argument");
}
const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?$/;
if (!semver.test(version) || !semver.test(minimumVersion)) throw new Error("invalid release version");
const compare = (left, right) => {
  const [lCore, lPre] = left.split("-", 2);
  const [rCore, rPre] = right.split("-", 2);
  const l = lCore.split(".").map(Number);
  const r = rCore.split(".").map(Number);
  for (let i = 0; i < 3; i++) if (l[i] !== r[i]) return l[i] - r[i];
  if (lPre == null && rPre != null) return 1;
  if (lPre != null && rPre == null) return -1;
  return (lPre ?? "").localeCompare(rPre ?? "");
};
if (compare(minimumVersion, version) > 0) throw new Error("minimum supported version exceeds release version");

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
console.log(`built signed stable manifest for ${version}`);
