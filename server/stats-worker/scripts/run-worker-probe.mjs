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

// A token-gated deployment answers every route 401, which used to reach the verdict
// logic as an unrecognised body and print "INCONCLUSIVE: inspect JSON above".
const headers = {
  Accept: "application/json",
  ...(process.env.WORKER_TOKEN ? { "X-BedwarsQol-Token": process.env.WORKER_TOKEN } : {}),
};

const res = await fetch(url, { headers });
const text = await res.text();

if (res.status === 401) {
  console.error("Unauthorized (401): this deployment is token-gated. Set WORKER_TOKEN to one of its STATS_TOKEN entries.");
  console.error(text.slice(0, 200));
  process.exit(1);
}

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
