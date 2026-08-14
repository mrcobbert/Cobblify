/** Parse aggregate + per-mode Bedwars stats from hypixel.net/player HTML. */

const MODE_PREFIXES = {
  solo: "Solo ",
  doubles: "Doubles ",
  threes: "3v3v3v3 ",
  fours: "4v4v4v4 ",
};

export function parseBedwarsFromHtml(html, player) {
  const lower = html.toLowerCase();
  const idx = lower.indexOf("stats-content-bedwars");
  if (idx < 0) {
    if (html.length < 50_000 && CHALLENGE_RE.test(html)) {
      return { success: false, state: "ERROR", error: "cloudflare_block", displayName: player };
    }
    return { success: false, state: "NICKED", displayName: player };
  }

  const section = html.slice(idx, idx + 20_000);
  const text = section.replace(/<[^>]+>/g, "\n");
  const tokens = text.split(/\n/).map((s) => s.trim()).filter(Boolean);

  // The forum renders the Bedwars section as flat "<label>\n<value>" pairs, with
  // per-mode rows prefixed (e.g. "Solo Final Kills"). Exact-label matching keeps
  // "Kills" from colliding with "Final Kills" or "Solo Kills".
  const overall = readMode(tokens, "");

  // We located the Bedwars section but resolved none of the expected overall labels.
  // That means the page markup changed (or was a challenge/partial render), not that
  // the player is new — fail loudly instead of silently reporting them as [New].
  if (overall.found === 0) {
    return { success: false, state: "ERROR", error: "parse_failed", displayName: player };
  }

  const modes = {};
  for (const key of Object.keys(MODE_PREFIXES)) {
    modes[key] = stripFound(readMode(tokens, MODE_PREFIXES[key]));
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
    modes,
  };

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
      const n = parseInt(raw, 10);
      return Number.isFinite(n) ? n : 0;
    }
  }
  return null;
}

// Actual interstitial markers only. `challenge-platform` (Cloudflare's beacon script) ships on
// every proxied page, successful ones included, so it cannot distinguish a challenge from content.
const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|attention required/i;
