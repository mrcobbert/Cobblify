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
