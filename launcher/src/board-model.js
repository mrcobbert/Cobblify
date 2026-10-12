// The stats board's view model: what the board draws for one v2 lobby.json snapshot, as plain data. Every decision
// on a row was made in the mod (row-model.js arranges it); order, party, own team, averages and the Target come from
// roster-identity.js. This file only shapes them for board-render.js and derives nothing.

import {
  activeFirst,
  dashboardParty,
  ownTeam,
  rankedOpponentTeams,
  sortRoster,
  withoutPlayers,
} from "./roster-identity.js";
import { colorClass, rowModel, sectionSpans, tierOf } from "./row-model.js";

/** "§4" → "4"; null when the row carries no colour. */
const codeOf = (color) => colorClass(color).slice(3) || null;

/** The rank as [{c, t}] spans in the mod's § colours; a rank with no codes is one uncoloured span. */
function rankOf(p) {
  const spans = sectionSpans(p?.rankCodes);
  if (spans.length) return spans.map((s) => ({ c: s.cls ? s.cls.slice(3) : null, t: s.text }));
  const raw = String(p?.rank ?? "").trim();
  return raw ? [{ c: null, t: raw }] : [];
}

/**
 * One player line. `chips` are the row model's chips in its order ({kind, text, mc}); `tags` are the mod's tags
 * with the code the in-game tab prints ([BC]); `cells` hold the row model's text (null while resolving).
 */
export function boardRow(p) {
  const m = rowModel(p);
  return {
    name: String(p?.name ?? ""),
    rank: rankOf(p),
    kind: m.kind,
    tier: tierOf(p),
    cheater: p?.cheater === true,
    out: m.classes.includes("is-out"),
    eliminated: m.classes.includes("is-eliminated"),
    // a nicked row's "Nicked" chip says the row's state, like "resolving": the reveal (nick→X) is the other nick chip
    chips: m.chips.map((c) => ({ kind: c.cls === "nick" && m.kind === "nicked" ? "state" : c.cls, text: c.text, mc: codeOf(c.color) })),
    cells: m.cells.map((c) => (c.skeleton ? { text: null, muted: false } : { text: c.text, muted: !!c.muted })),
    tags:
      m.kind === "stats"
        ? (p?.chips ?? []).map((c) => ({ code: String(c?.code ?? ""), label: String(c?.label ?? ""), mc: codeOf(c?.color) }))
        : [],
  };
}

const rowsOf = (players) => (players ?? []).map(boardRow);

/** The party keeps its own order (you, your party, the rest of your team); out members drop to its end. */
function partyBlock(list) {
  if (!list || !list.length) return null;
  return { kind: "party", head: "Your Party", note: String(list.length), rows: rowsOf(activeFirst(list)) };
}

function viewLobby(d) {
  const all = d.players ?? [];
  const party = dashboardParty(d);
  const rest = sortRoster(withoutPlayers(all, party));
  const blocks = [];
  if (rest.length) blocks.push({ kind: "section", head: "Lobby", note: String(rest.length), rows: rowsOf(rest) });
  const pb = partyBlock(party);
  if (pb) blocks.push(pb);
  return { context: "LOBBY", title: "Main Lobby", sub: `· ${all.length} players`, blocks };
}

function viewQueue(d) {
  const all = d.players ?? [];
  const party = dashboardParty(d);
  const rest = sortRoster(withoutPlayers(all, party));
  const parties = typeof d.partyCount === "number" ? `${d.partyCount} parties · ` : "";
  // The head stays; its helper note and the foot line went with the 0.18 board (the user, 2026-10-11).
  const blocks = [{ kind: "section", head: "Chatted", note: null, rows: rowsOf(rest) }];
  const pb = partyBlock(party);
  if (pb) blocks.push(pb);
  return { context: "QUEUE", title: d.mode ? `${d.mode} Queue` : "Queue", sub: `· ${parties}${all.length} known`, blocks };
}

function viewGame(d) {
  const blocks = rankedOpponentTeams(d).map(({ team, players, agg, standing, target }) => ({
    kind: "team",
    team: team.name,
    standing,
    avg: agg,
    target,
    rows: rowsOf(players),
  }));
  const pb = partyBlock(dashboardParty(d));
  if (pb) blocks.push(pb);
  return {
    context: "GAME",
    title: d.mode ? String(d.mode) : "Live Match",
    sub: `· ${(d.teams ?? []).length} teams`,
    ownTeam: ownTeam(d)?.name ?? null,
    blocks,
  };
}

/** The roster view for an eligible snapshot: QUEUE, GAME, anything else the lobby. */
export function boardView(d) {
  if (d.context === "QUEUE") return viewQueue(d);
  if (d.context === "GAME") return viewGame(d);
  return viewLobby(d);
}

/** Player lines in a view (section heads and gaps not counted). */
export const rowCount = (view) => (view.blocks ?? []).reduce((n, b) => n + (b.rows?.length ?? 0), 0);
