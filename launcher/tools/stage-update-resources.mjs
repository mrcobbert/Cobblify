#!/usr/bin/env node
import { createHash } from "node:crypto";
import { copyFile, mkdir, readdir, writeFile } from "node:fs/promises";
import path from "node:path";

const [resourcesDir, lunarJar, agentJar, forgeJar, version] = process.argv.slice(2);
if (![resourcesDir, lunarJar, agentJar, forgeJar, version].every(Boolean)) {
  throw new Error("usage: stage-update-resources.mjs <resources-dir> <lunar-jar> <agent-jar> <forge-jar> <version>");
}
if (!/^[0-9A-Za-z._+-]+$/.test(version)) throw new Error("unsafe version");

await mkdir(resourcesDir, { recursive: true });
const existing = (await readdir(resourcesDir)).filter((name) => name.endsWith(".jar") || name === "manifest.json");
if (existing.length) throw new Error(`refusing to overwrite staged resources: ${existing.join(", ")}`);

const entries = {};
for (const [key, source] of [["mod", lunarJar], ["agent", agentJar], ["forge", forgeJar]]) {
  const name = path.basename(source);
  if (!/^[0-9A-Za-z._+-]+\.jar$/.test(name)) throw new Error(`unsafe jar name: ${name}`);
  const target = path.join(resourcesDir, name);
  await copyFile(source, target);
  const bytes = await import("node:fs/promises").then(({ readFile }) => readFile(target));
  entries[key] = { name, sha256: createHash("sha256").update(bytes).digest("hex") };
}

const manifest = {
  mod_jar: entries.mod.name,
  agent_jar: entries.agent.name,
  mod_version: version,
  mod_sha256: entries.mod.sha256,
  agent_sha256: entries.agent.sha256,
  forge_jar: entries.forge.name,
  forge_sha256: entries.forge.sha256,
};
await writeFile(path.join(resourcesDir, "manifest.json"), `${JSON.stringify(manifest)}\n`, { flag: "wx" });
console.log(`staged three verified launcher resources for ${version}`);
