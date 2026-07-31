/**
 * Shmeado fallback source (https://shmeado.club): scrapes the server-rendered player page while
 * hypixel.net is behind its edge block. FALLBACK ONLY - scrape.js routes here exclusively when the
 * hypixel breaker is set, so this module never touches claimOrigin/noteOrigin429/writeBlocked and
 * never sets retry429. It has its own trivial politeness pacer instead.
 *
 * The page embeds the raw Hypixel API player JSON as `window.player={...}` near the page END
 * (unquoted identifier keys, backtick strings - NOT JSON), so extraction is a backtick-aware
 * balanced-brace scan that tracks depth, not a JSON.parse. Shmeado normalizes the projection:
 * never-played accounts still carry all six counters as explicit `:0` keys (verified on the
 * committed Notch fixture), so a missing key ALWAYS means layout drift and the parser fails
 * closed - degraded to today's blocked behavior, never wrong stats.
 */

import { writeCached, NEG_TTL_SEC, NICKED_TTL_SEC } from "./cache.js";

const SHMEADO_UA =
  "bedwarsqol-stats-worker (Minecraft mod stats backend; low-volume fallback)";

const FETCH_TIMEOUT_MS = 15_000; // observed worst page 7.3 s; no retries
const SHMEADO_SPACING_MS = 1500; // per-isolate politeness toward a hobby site

const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|challenge-platform|attention required/i;

const MARKER = "window.player={";

const COUNTER_KEYS = [
  "wins_bedwars", "losses_bedwars", "kills_bedwars", "deaths_bedwars",
  "final_kills_bedwars", "final_deaths_bedwars",
];

// Shmeado rank strings provably identical to the forum rank-badge codes (HypixelRanks.java).
// `None` = proven rankless (rank:null, matching the forum path). ANYTHING else fails the whole
// lookup: already-shipped clients coerce an absent/omitted rank to rankless, which would break
// the denick elevated-rank gate, so an unproven rank must never ride a success body.
const RANK_MAP = {
  "None": null,
  "VIP": "vip",
  "VIP+": "vip_plus",
  "MVP": "mvp",
  "MVP+": "mvp_plus",
  "MVP++": "superstar",
  "YOUTUBE": "youtuber",
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Per-isolate pacer: the ORIGIN_GATE pattern minus lanes/429 machinery. `lastStart` is a past
// fact assigned only when the spacing has elapsed, so consecutive starts are >= the spacing apart.
const PACER = { lastStart: 0 };

async function claimShmeado() {
  while (true) {
    const now = Date.now();
    const earliest = PACER.lastStart + SHMEADO_SPACING_MS;
    if (now >= earliest) {
      PACER.lastStart = now;
      return;
    }
    await sleep(earliest - now);
  }
}

/**
 * Walk one brace-balanced object starting at text[open] === "{", skipping backtick-quoted spans
 * (the object embeds ~200 template strings that may contain braces or key-shaped text). Records
 * every occurrence of a `wanted` key found at a KEY position (right after `{` or `,`, never inside
 * a string) with its depth relative to this object (direct fields = depth 1), its integer value
 * when the value is a plain digit run terminated by `,` or `}` (else null), and the index of its
 * opening brace when the value is an object (else -1). Returns { end, entries }, or null when the
 * object (or a string) never closes - the fail-closed truncation signal.
 */
function scanObject(text, open, wanted) {
  const entries = [];
  let depth = 0;
  let i = open;
  let atKey = false; // the cursor sits where a key of the current depth may start
  while (i < text.length) {
    const c = text[i];
    if (c === "`") {
      i++;
      while (i < text.length && text[i] !== "`") i += text[i] === "\\" ? 2 : 1;
      if (i >= text.length) return null; // unterminated string
      i++;
      atKey = false;
      continue;
    }
    if (c === "{") { depth++; atKey = true; i++; continue; }
    if (c === "}") {
      depth--;
      if (depth === 0) return { end: i + 1, entries };
      atKey = false;
      i++;
      continue;
    }
    if (c === ",") { atKey = true; i++; continue; }
    if (/\s/.test(c)) { i++; continue; } // whitespace keeps the key position
    if (atKey && /[A-Za-z0-9_$]/.test(c)) {
      let j = i;
      while (j < text.length && /[A-Za-z0-9_$]/.test(text[j])) j++;
      if (text[j] === ":" && wanted.has(text.slice(i, j))) {
        const num = /^(\d+)[,}]/.exec(text.slice(j + 1, j + 24));
        entries.push({
          key: text.slice(i, j),
          depth,
          value: num ? Number(num[1]) : null,
          open: text[j + 1] === "{" ? j + 1 : -1,
        });
      }
      atKey = false;
      i = text[j] === ":" ? j + 1 : j;
      continue;
    }
    atKey = false;
    i++;
  }
  return null; // ran off the end before the object closed
}

/**
 * Parse a shmeado player page. Fail-closed: every ambiguity is `parse_failed` (transient
 * negative), never wrong stats, never NICKED, never NEVER_PLAYED-by-absence. Exported for unit
 * tests. Returns the same body shape as the hypixel path minus `modes` (unavailable on this
 * source) plus optional `bedwarsLevel`.
 */
export function parseShmeadoPlayer(html, requestedName) {
  const failed = { success: false, state: "ERROR", error: "parse_failed", displayName: requestedName };

  let count = 0;
  let first = -1;
  for (let i = html.indexOf(MARKER); i >= 0; i = html.indexOf(MARKER, i + 1)) {
    if (first < 0) first = i;
    count++;
  }
  if (count === 0) {
    // Not-found pages echo the requested name: `Invalid Name/UUID '<name>'`. The FULL quoted
    // token must equal the requested name (a prefix echo must not match), else this is an
    // ambiguous page (outage, redesign, generic error) and is only briefly negative-cached.
    const m = /Invalid Name\/UUID '([^']{0,64})'/.exec(html);
    if (m && m[1].toLowerCase() === requestedName.toLowerCase()) {
      return { success: false, state: "NICKED", displayName: requestedName };
    }
    return failed;
  }
  if (count > 1) return failed;

  const open = first + MARKER.length - 1;
  const scriptEnd = html.indexOf("</script>", open);
  const slice = html.slice(open, scriptEnd >= 0 ? scriptEnd : html.length);

  // Identity gate: the canonical name at the object head must match the requested name
  // case-insensitively. A page that cannot prove whose stats it shows produces nothing -
  // there is deliberately NO fall-back to the requested name.
  const idm = /^\{name:`([A-Za-z0-9_]{1,16})`/.exec(slice);
  if (!idm || idm[1].toLowerCase() !== requestedName.toLowerCase()) return failed;
  const displayName = idm[1];

  const outer = scanObject(slice, 0, new Set(["rank", "stats", "bedwars", "bedwars_level"]));
  if (!outer) return failed;

  // The stats container: exactly one, a DIRECT field of the player object (the fixture shape
  // is window.player -> stats -> bedwars).
  const statsEntries = outer.entries.filter((e) => e.key === "stats" && e.depth === 1);
  if (statsEntries.length !== 1 || statsEntries[0].open < 0) return failed;

  // The bedwars object: exactly one anywhere in the player object (string-context anchors were
  // already excluded by the scan), AND it must be the direct child of stats - a same-named
  // object at any other location (wrapper:{bedwars:{...}}) is layout drift, not the projection.
  // Absent is NEVER never-played: Notch proves never-played accounts still render the object.
  const containers = outer.entries.filter((e) => e.key === "bedwars");
  const statsScan = scanObject(slice, statsEntries[0].open, new Set(["bedwars"]));
  if (!statsScan) return failed;
  const direct = statsScan.entries.filter((e) => e.key === "bedwars" && e.depth === 1);
  if (containers.length !== 1 || direct.length !== 1 || direct[0].open !== containers[0].open) {
    return failed;
  }

  // Rank: `rank:{rank:`X`,...}` as a DIRECT field of the player object, required. Only the
  // explicit `None` string proves rankless (Notch fixture); an ABSENT rank structure proves
  // layout drift and fails the lookup - a success body with a fabricated rankless would let
  // already-shipped clients drop a genuine elevated-rank denick. hasOwnProperty guards against
  // prototype-key strings ("constructor").
  const ranks = outer.entries.filter((e) => e.key === "rank" && e.depth === 1);
  if (ranks.length !== 1 || ranks[0].open < 0) return failed;
  const rm = /^\{rank:`([^`]{0,40})`/.exec(slice.slice(ranks[0].open, ranks[0].open + 64));
  if (!rm || !Object.prototype.hasOwnProperty.call(RANK_MAP, rm[1])) return failed;
  const rank = RANK_MAP[rm[1]];

  // The six counters must each appear EXACTLY ONCE as a DIRECT (depth-1) numeric field of the
  // bedwars object. Nested objects inside it (a hypothetical historical:{...}) contribute
  // nothing; missing, duplicated, or non-numeric keys fail the whole parse. No missing->0
  // defaulting exists: shmeado writes zeros explicitly.
  const inner = scanObject(slice, containers[0].open, new Set(COUNTER_KEYS));
  if (!inner) return failed;
  const counters = {};
  for (const key of COUNTER_KEYS) {
    const direct = inner.entries.filter((e) => e.key === key && e.depth === 1);
    if (direct.length !== 1 || direct[0].value == null) return failed;
    counters[key] = direct[0].value;
  }

  const overall = {
    wins: counters.wins_bedwars,
    losses: counters.losses_bedwars,
    kills: counters.kills_bedwars,
    deaths: counters.deaths_bedwars,
    finalKills: counters.final_kills_bedwars,
    finalDeaths: counters.final_deaths_bedwars,
  };

  const body = {
    success: true,
    displayName,
    rank,
    finalKills: overall.finalKills,
    finalDeaths: overall.finalDeaths,
    wins: overall.wins,
    losses: overall.losses,
    kills: overall.kills,
    deaths: overall.deaths,
    overall,
    state: Object.values(overall).every((v) => v === 0) ? "NEVER_PLAYED" : "OK",
  };

  // Star: optional. Exactly one numeric bedwars_level anywhere (it lives in the raw
  // achievements map; legitimately absent on never-played accounts) and > 0, else omitted -
  // the star can only be absent, never wrong-but-present.
  const levels = outer.entries.filter((e) => e.key === "bedwars_level" && e.value != null);
  if (levels.length === 1 && levels[0].value > 0) body.bedwarsLevel = levels[0].value;

  return body;
}

/**
 * Scrape one player from shmeado, parse, and cache. Returns { body } - never retry429, so
 * neither caller retry loop re-runs it or feeds the hypixel gate. Cache policy: success 900 s
 * L1+KV (source-agnostic, same keys/TTLs as primary), NICKED durable, parse_failed 90 s L1-only.
 * Transport failures (fetch error, timeout, non-200, challenge) return today's exact blocked
 * short-circuit body UNCACHED - the pacer bounds retry pressure, and a cached copy would
 * outlive the breaker flag's own TTL (same rationale as scrape.js's short-circuit).
 */
export async function shmeadoScrapeAndCache(player, env, ctx) {
  await claimShmeado();
  const base = (env && env.SHMEADO_BASE) || "https://shmeado.club";
  const target = `${base}/player/stats/${encodeURIComponent(player)}/`;
  const aborter = new AbortController();
  const timer = setTimeout(() => aborter.abort(), FETCH_TIMEOUT_MS);
  let html;
  try {
    // Full-body read: the player object sits at the page END, so no early-abort streaming.
    const response = await fetch(target, {
      method: "GET",
      headers: {
        "User-Agent": SHMEADO_UA,
        Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      },
      redirect: "follow",
      signal: aborter.signal,
    });
    if (response.status < 200 || response.status >= 300) {
      try { await response.body?.cancel(); } catch (_) {}
      return { body: blockedBody(player) };
    }
    html = await response.text();
  } catch (_) {
    return { body: blockedBody(player) }; // fetch error / timeout: uncached
  } finally {
    clearTimeout(timer);
  }

  // A future shmeado bot-protection page is a transport-class failure, not layout drift.
  if (CHALLENGE_RE.test(html) && !html.includes(MARKER)) {
    return { body: blockedBody(player) };
  }

  const parsed = parseShmeadoPlayer(html, player);
  if (parsed.success === true) {
    writeCached(player, parsed, env, ctx);
  } else {
    writeCached(player, parsed, env, ctx, parsed.state === "NICKED" ? NICKED_TTL_SEC : NEG_TTL_SEC);
  }
  return { body: parsed };
}

/** Byte-identical to scrape.js's blocked short-circuit body (no httpStatus field). */
function blockedBody(player) {
  return { success: false, state: "ERROR", displayName: player, error: "blocked_by_cloudflare" };
}
