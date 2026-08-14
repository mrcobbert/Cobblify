#!/usr/bin/env node
/**
 * Hit a deployed stats Worker and print verdict.
 * Usage: WORKER_URL=https://bedwarsqol-stats.<you>.workers.dev npm run test:worker
 */

const base = process.env.WORKER_URL?.replace(/\/+$/, "");
const player = process.env.PLAYER || "beepor";

if (!base) {
  console.error("Set WORKER_URL to your deployed workers.dev URL (no trailing path).");
  console.error("Example: WORKER_URL=https://bedwarsqol-stats.example.workers.dev npm run test:worker");
  process.exit(1);
}

const url = `${base}/test/${encodeURIComponent(player)}`;
console.log(`Worker probe: GET ${url}\n`);

const res = await fetch(url, { headers: { Accept: "application/json" } });
const text = await res.text();
let data;
try {
  data = JSON.parse(text);
} catch {
  console.error("Non-JSON response:", text.slice(0, 500));
  process.exit(1);
}

console.log(JSON.stringify(data, null, 2));
console.log("");

if (data.verdict === "PASS") {
  console.log("PASS DEFINITIVE: Cloudflare Worker egress CAN scrape Hypixel player pages today.");
  process.exit(0);
}
if (data.verdict === "BLOCKED") {
  console.log("FAIL DEFINITIVE: Worker egress is BLOCKED. Use Oracle VM (or non-CF egress), not plain Workers.");
  process.exit(2);
}
console.log("? INCONCLUSIVE: inspect JSON above.");
process.exit(1);
