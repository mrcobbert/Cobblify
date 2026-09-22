// Characterize hypixel's origin rate limit AS SEEN FROM THIS WORKER'S egress IP.
// Hits the single endpoint with fresh=1 (one direct scrape, bypasses the politeness pool)
// at controlled intervals, counts 429s. Minimal volume (~one Bedwars game's worth).
//
// WORKER_TOKEN, when set, is sent as X-BedwarsQol-Token; a gated deployment answers 401
// to everything without it. RATE_PROBE_N overrides BOTH sweep counts and drops the pause
// between the sweeps - a test affordance, so leave it unset for a real measurement.
const URL = process.env.WORKER_URL;
if (!URL) {
  console.error("set WORKER_URL to your deployed Worker's base url");
  process.exit(1);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const parsedN = Number.parseInt(process.env.RATE_PROBE_N ?? "", 10);
const forcedN = Number.isInteger(parsedN) && parsedN > 0 ? parsedN : null;
const headers = {
  Accept: "application/json",
  ...(process.env.WORKER_TOKEN ? { "X-BedwarsQol-Token": process.env.WORKER_TOKEN } : {}),
};

async function hit(name) {
  const t0 = Date.now();
  try {
    const r = await fetch(`${URL}/bedwars/${name}?fresh=1`, { headers });
    const j = await r.json().catch(() => ({}));
    return { ms: Date.now() - t0, status: r.status, state: j.state, err: j.error };
  } catch (e) {
    return { ms: Date.now() - t0, status: 0, err: String(e) };
  }
}

async function sweep(intervalMs, n, tag) {
  let ok = 0, r429 = 0, other = 0;
  for (let i = 0; i < n; i++) {
    const name = "Rp" + tag + i + Math.floor(Date.now() / 1000) % 1000; // distinct, valid, uncached
    const res = await hit(name.slice(0, 16));
    const is429 = res.err === "http_429" || res.status === 429;
    if (is429) r429++; else if (res.state || res.status === 200) ok++; else other++;
    process.stdout.write(`  [${tag}] ${name.slice(0,16).padEnd(16)} ${res.status} ${(res.err||res.state||"ok").padEnd(13)} ${res.ms}ms\n`);
    if (i < n - 1) await sleep(intervalMs);
  }
  const rate = (1000 / intervalMs).toFixed(2);
  console.log(`>> interval=${intervalMs}ms (~${rate}/s): ok=${ok} 429=${r429} other=${other}\n`);
  return { intervalMs, rate, ok, r429 };
}

console.log(`probing ${URL}\n`);
// Start gentle (should be clean), then push past the suspected ~1.7/s threshold.
await sweep(650, forcedN ?? 5, "A");   // ~1.5/s
if (!forcedN) await sleep(3000);
await sweep(350, forcedN ?? 6, "B");   // ~2.9/s — expect 429s if the ~1.7/s ceiling is real
console.log("done");
