const key = (name) => String(name ?? "").trim().toLowerCase();

export function aliases(player) {
  const out = new Set();
  if (key(player?.name)) out.add(key(player.name));
  if (key(player?.realName)) out.add(key(player.realName));
  return out;
}

export function samePlayer(a, b) {
  const right = aliases(b);
  for (const name of aliases(a)) {
    if (right.has(name)) return true;
  }
  return false;
}

/** Replace retained /party-list rows with their current tab rows when nick aliases permit it. */
export function resolvedParty(d) {
  const live = d?.players ?? [];
  const out = [];
  for (const retained of d?.yourParty ?? []) {
    const current = live.find((player) => samePlayer(retained, player)) ?? retained;
    if (!out.some((player) => samePlayer(player, current))) out.push(current);
  }
  return out;
}

/** Self is authoritative; party overlap covers brief self-profile/tab churn. */
export function ownTeam(d) {
  const teams = d?.teams ?? [];
  if (key(d?.self)) {
    const self = { name: d.self };
    const direct = teams.find((team) =>
      (team.players ?? []).some((player) => samePlayer(self, player)),
    );
    if (direct) return direct;
  }

  const party = resolvedParty(d);
  let best = null;
  let bestMatches = 0;
  for (const team of teams) {
    const matches = (team.players ?? []).filter((player) =>
      party.some((member) => samePlayer(member, player)),
    ).length;
    if (matches > bestMatches) {
      best = team;
      bestMatches = matches;
    }
  }
  return bestMatches > 0 ? best : null;
}

/** During a match, the own-team roster is the strongest source for the pinned local group. */
export function dashboardParty(d) {
  const out = resolvedParty(d);
  const team = d?.context === "GAME" ? ownTeam(d) : null;
  for (const player of team?.players ?? []) {
    if (!out.some((member) => samePlayer(member, player))) out.push(player);
  }
  return out;
}

export function withoutPlayers(players, excluded) {
  return (players ?? []).filter(
    (player) => !(excluded ?? []).some((member) => samePlayer(member, player)),
  );
}

/** Only these rows may participate in opponent sections or Target selection. */
export function opponentTeams(d) {
  const local = ownTeam(d);
  const party = dashboardParty(d);
  return (d?.teams ?? [])
    .filter((team) => team !== local)
    .map((team) => ({ ...team, players: withoutPlayers(team.players, party) }))
    .filter((team) => team.players.length > 0);
}

// ── presence ─────────────────────────────────────────────────────────────────
// The mod remembers everyone seen in a game and marks who is really out; a
// missing field is an older mod jar, which only ever listed present players.

export const PRESENCE_BADGE = { DISCONNECTED: "DC", ELIMINATED: "OUT", MISSING: "GONE" };

export function presenceOf(p) {
  return p?.presence ?? "ACTIVE";
}

export function isActive(p) {
  return presenceOf(p) === "ACTIVE";
}

/** Short chip label for a non-active row; null for an active one. */
export function presenceBadge(p) {
  return PRESENCE_BADGE[presenceOf(p)] ?? null;
}

/** Mean FKDR over active, resolved players; null when there are none. */
export function teamAvgFkdr(players) {
  const ok = (players ?? []).filter((p) => isActive(p) && p.state === "OK");
  return ok.length ? ok.reduce((s, p) => s + (p.fkdr ?? 0), 0) / ok.length : null;
}

/** "active" (has a numeric average) · "unresolved" (active players, none resolved) · "eliminated" (nobody active). */
export function teamStanding(team) {
  const players = team?.players ?? [];
  if (!players.some(isActive)) return "eliminated";
  return teamAvgFkdr(players) === null ? "unresolved" : "active";
}

// Active OK players high→low by FKDR, then active unresolved rows in their
// given order, then everyone who is out - in the same order. An out player
// never floats above someone still in the game.
export function sortRoster(players) {
  const list = (players ?? []).slice();
  const ranked = list
    .filter((p) => isActive(p) && p.state === "OK")
    .sort((a, b) => (b.fkdr ?? 0) - (a.fkdr ?? 0));
  const unresolved = list.filter((p) => isActive(p) && p.state !== "OK");
  const out = list.filter((p) => !isActive(p));
  return ranked.concat(unresolved, out);
}

/** Given order, except that players who are out of the game move to the end. */
export function activeFirst(players) {
  const list = players ?? [];
  return list.filter(isActive).concat(list.filter((p) => !isActive(p)));
}

const STANDING_RANK = { active: 0, unresolved: 1, eliminated: 2 };

/**
 * Opponent teams in display order with everything the game view needs:
 * numeric averages first (high→low), then unresolved, then eliminated. Only
 * the first team can be the Target, and only when its average is a number -
 * a board with no resolved active player has no Target at all.
 */
export function rankedOpponentTeams(d) {
  const ranked = opponentTeams(d)
    .map((team) => ({
      team,
      players: sortRoster(team.players),
      agg: teamAvgFkdr(team.players),
      standing: teamStanding(team),
      target: false,
    }))
    .sort((a, b) => {
      const s = STANDING_RANK[a.standing] - STANDING_RANK[b.standing];
      return s !== 0 ? s : (b.agg ?? -1) - (a.agg ?? -1);
    });
  if (ranked.length && ranked[0].agg !== null) ranked[0].target = true;
  return ranked;
}
