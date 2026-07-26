/**
 * Live sanitized Coral v3 probe - the REQUIRED pre-ship compatibility gate.
 *
 * Reads the owner's key from ~/.urchin-key (never echoed, never logged, never committed)
 * and records ONLY sanitized status/schema observations to stdout, suitable for pasting
 * into .ai/research/urchin/PROBES.md.
 *
 * Usage: node scripts/urchin-probe.mjs [taggedName] [untaggedName]
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const BASE = process.env.URCHIN_BASE || "https://api.urchin.gg";

let key;
try {
  key = readFileSync(join(homedir(), ".urchin-key"), "utf8").trim();
} catch {
  console.error("No ~/.urchin-key file. Create it with your key (chmod 600) and re-run.");
  process.exit(2);
}
if (!key) { console.error("~/.urchin-key is empty."); process.exit(2); }

const taggedName = process.argv[2] || "TaggedExample";
const untaggedName = process.argv[3] || "Technoblade";

function describeTags(tags) {
  if (!Array.isArray(tags)) return "NOT-AN-ARRAY";
  return `${tags.length} tag(s): ` + tags
    .map((t) => `{type=${t.tag_type}, reason.len=${(t.reason || "").length}, added_on=${typeof t.added_on}, expires_at=${t.expires_at === undefined ? "ABSENT" : t.expires_at === null ? "null" : typeof t.expires_at}}`)
    .join(", ");
}

async function probe(label, path, init, requestedUuids) {
  try {
    const res = await fetch(BASE + path, {
      ...init,
      redirect: "error",
      headers: { ...(init && init.headers), "X-API-Key": key },
    });
    let body = null;
    // 401 has an EMPTY body, so the status is reported before (and regardless of) any parse.
    try { body = await res.json(); } catch { /* non-JSON / empty */ }
    console.log(`\n[${label}] HTTP ${res.status}`);
    if (body && body.error) console.log(`  error (ErrorResponse): ${body.error}`);
    if (body && body.tags !== undefined) console.log(`  tags: ${describeTags(body.tags)}`);
    if (body && body.uuid) console.log(`  uuid: present (${String(body.uuid).length} chars)`);
    if (body && body.displayname !== undefined) console.log(`  displayname: present`);
    if (body && body.players) {
      for (const [k, v] of Object.entries(body.players)) {
        console.log(`  players[${k.length}-char key]: ${describeTags(v)}`);
      }
      // Present-with-[] means CHECKED AND CLEAN; absent means the entry was SKIPPED. The
      // worker must never conflate them, so the probe reports the split explicitly.
      if (requestedUuids) {
        const present = new Set(Object.keys(body.players).map((k) => k.toLowerCase().replace(/-/g, "")));
        const absent = requestedUuids.filter((u) => !present.has(u.toLowerCase().replace(/-/g, "")));
        console.log(`  requested=${requestedUuids.length} present=${present.size} ABSENT=${absent.length}`);
      }
    }
    const rl = ["x-ratelimit-limit", "x-ratelimit-remaining", "ratelimit-limit", "ratelimit-remaining"]
      .map((h) => (res.headers.get(h) != null ? `${h}=${res.headers.get(h)}` : null))
      .filter(Boolean);
    if (rl.length) console.log(`  ratelimit headers: ${rl.join(" ")}`);
  } catch (e) {
    console.log(`\n[${label}] FETCH ERROR: ${String(e && e.message).slice(0, 120)}`);
  }
}

console.log(`Urchin Coral v3 probe - ${new Date().toISOString()} - base ${BASE}`);
await probe("single tagged", `/v3/player/tags?player=${encodeURIComponent(taggedName)}`);
await probe("single untagged", `/v3/player/tags?player=${encodeURIComponent(untaggedName)}`);
await probe("unknown player (expect 404)", `/v3/player/tags?player=DefinitelyNotARealNameXq`);
const batchUuids = ["069a79f444e94726a5befca90e38aaf5", "malformed"];
await probe("batch (2 uuids, 1 malformed -> expect it ABSENT)", `/v3/players`, {
  method: "POST",
  headers: { "content-type": "application/json" },
  body: JSON.stringify({ uuids: batchUuids }),
}, batchUuids);

// Invalid-key probe uses a DIFFERENT random key, never derived from the real one.
const badKey = "invalid-probe-key-000000";
try {
  const res = await fetch(`${BASE}/v3/player/tags?player=${encodeURIComponent(untaggedName)}`, {
    redirect: "error",
    headers: { "X-API-Key": badKey },
  });
  const text = await res.text();
  // Both 401 (bad key) and 403 (locked/forbidden key) map to "rejected" in the worker.
  console.log(`\n[invalid key (expect 401, empty body)] HTTP ${res.status} body.len=${text.length}`);
} catch (e) {
  console.log(`\n[invalid key] FETCH ERROR: ${String(e && e.message).slice(0, 120)}`);
}
console.log("\nDone. Paste this output into .ai/research/urchin/PROBES.md (it contains no key material).");
