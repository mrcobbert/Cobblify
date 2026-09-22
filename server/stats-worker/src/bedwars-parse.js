/** Parse aggregate + per-mode Bedwars stats from hypixel.net/player HTML. */

const SECTION_MARKER_PREFIX = "stats-content-";
const BEDWARS_MARKER = SECTION_MARKER_PREFIX + "bedwars";

const MODE_PREFIXES = {
  solo: "Solo ",
  doubles: "Doubles ",
  threes: "3v3v3v3 ",
  fours: "4v4v4v4 ",
};

export function parseBedwarsFromHtml(html, player) {
  const lower = html.toLowerCase();
  const idx = lower.indexOf(BEDWARS_MARKER);
  if (idx < 0) {
    if (html.length < 50_000 && CHALLENGE_RE.test(html)) {
      return { success: false, state: "ERROR", error: "cloudflare_block", displayName: player };
    }
    return { success: false, state: "NICKED", displayName: player };
  }

  // Section ownership: the forum gives every game its own `stats-content-<game>` block (one open
  // and one close marker each), so the Bedwars section ends at the NEXT such marker. A flat window
  // reached past it - on the live page the Bedwars content ends 19,257 bytes after its marker and
  // the SkyWars marker sits at +21,367 (measured 2026-09-22) - so a Bedwars label missing from a
  // drifted page could be answered by the following game's identically-named row.
  const next = lower.indexOf(SECTION_MARKER_PREFIX, idx + BEDWARS_MARKER.length);
  const section = html.slice(idx, next < 0 ? idx + 20_000 : next);
  const text = section.replace(/<[^>]+>/g, "\n");
  const tokens = text.split(/\n/).map((s) => s.trim()).filter(Boolean);

  // The forum renders the Bedwars section as flat "<label>\n<value>" pairs, with
  // per-mode rows prefixed (e.g. "Solo Final Kills"). Exact-label matching keeps
  // "Kills" from colliding with "Final Kills" or "Solo Kills".
  const overall = readMode(tokens, "");

  // We located the Bedwars section but at least one of the six overall labels did not resolve.
  // That means the page markup changed (or was a challenge/partial render), not that the player
  // is new: the forum prints all six overall rows even for a never-played account
  // (hypixel.net/player/Notch, verified live 2026-09-22 - all six present at 0, no empty cells).
  // A partial parse would be served as zeros and cached for 15 minutes, so fail loudly instead;
  // parse_failed is a transient negative (L1 only, 90 s) and self-heals. The PER-MODE policy is
  // deliberately different - a partial per-mode parse is still served, see the guard below.
  if (overall.found < 6) {
    return { success: false, state: "ERROR", error: "parse_failed", displayName: player };
  }

  const modes = {};
  let modesFound = 0;
  for (const key of Object.keys(MODE_PREFIXES)) {
    const m = readMode(tokens, MODE_PREFIXES[key]);
    modesFound += m.found;
    modes[key] = stripFound(m);
  }

  const body = {
    success: true,
    displayName: player,
    // Flat overall fields kept for backward compatibility with older clients/cache.
    finalKills: overall.finalKills,
    finalDeaths: overall.finalDeaths,
    wins: overall.wins,
    losses: overall.losses,
    kills: overall.kills,
    deaths: overall.deaths,
    overall: stripFound(overall),
  };

  // The forum prints every per-mode row even for a never-played account (verified 2026-09-20:
  // Notch's page carries all 24 "Solo/Doubles/3v3v3v3/4v4v4v4 <stat>" labels at 0), so overall
  // resolving while NO per-mode label resolves is markup drift, not a player state. Serving the
  // all-zero modes would make every forced /bw mode silently fall back to overall for everyone;
  // omitting them makes the client render "All" (the honest fallback) and flags the drift.
  if (modesFound === 0) {
    body.modesError = "parse_failed";
  } else {
    body.modes = modes;
  }

  if (isEmpty(overall)) {
    return { ...body, state: "NEVER_PLAYED" };
  }

  return { ...body, state: "OK" };
}

/** Read the six counters for one mode (prefix "" = overall). `found` counts resolved labels. */
function readMode(tokens, prefix) {
  const wins = readExact(tokens, prefix + "Wins");
  const losses = readExact(tokens, prefix + "Losses");
  const kills = readExact(tokens, prefix + "Kills");
  const deaths = readExact(tokens, prefix + "Deaths");
  const finalKills = readExact(tokens, prefix + "Final Kills");
  const finalDeaths = readExact(tokens, prefix + "Final Deaths");
  const found = [wins, losses, kills, deaths, finalKills, finalDeaths].filter((v) => v !== null).length;
  return {
    wins: wins ?? 0,
    losses: losses ?? 0,
    kills: kills ?? 0,
    deaths: deaths ?? 0,
    finalKills: finalKills ?? 0,
    finalDeaths: finalDeaths ?? 0,
    found,
  };
}

function stripFound(mode) {
  const { found, ...rest } = mode;
  return rest;
}

function isEmpty(mode) {
  return mode.wins === 0 && mode.losses === 0 && mode.kills === 0
    && mode.deaths === 0 && mode.finalKills === 0 && mode.finalDeaths === 0;
}

function readExact(tokens, label) {
  for (let i = 0; i < tokens.length - 1; i++) {
    if (tokens[i] === label) {
      const raw = tokens[i + 1].replace(/,/g, "");
      // An empty <td></td> contributes no token, so "the next token" is the next LABEL. Reading
      // that as 0 invented a value AND shifted the meaning of the rows after it, so anything that
      // is not a plain integer means this row's value is missing, not zero.
      if (!/^\d+$/.test(raw)) return null;
      return parseInt(raw, 10);
    }
  }
  return null;
}

// Actual interstitial markers only. `challenge-platform` (Cloudflare's beacon script) ships on
// every proxied page, successful ones included, so it cannot distinguish a challenge from content.
const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|attention required/i;
