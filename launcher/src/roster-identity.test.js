import test from "node:test";
import assert from "node:assert/strict";

import {
  dashboardParty,
  isActive,
  opponentTeams,
  ownTeam,
  presenceBadge,
  rankedOpponentTeams,
  resolvedParty,
  samePlayer,
  sortRoster,
  teamAvgFkdr,
  teamStanding,
  withoutPlayers,
} from "./roster-identity.js";

const p = (name, realName) => ({ name, realName });

test("identity matching is case-insensitive and follows a verified nick alias", () => {
  assert.equal(samePlayer(p("PartyFriend"), p("HiddenNick", "partyfriend")), true);
  assert.equal(samePlayer(p("PARTYFRIEND"), p("partyfriend")), true);
  assert.equal(samePlayer(p("SomeoneElse"), p("HiddenNick", "PartyFriend")), false);
});

test("retained party rows use the current nick and resolved live stats row", () => {
  const live = { ...p("HiddenNick", "PartyFriend"), state: "OK", fkdr: 9 };
  const d = { yourParty: [p("PartyFriend")], players: [live] };
  assert.deepEqual(resolvedParty(d), [live]);
  assert.deepEqual(withoutPlayers(d.players, resolvedParty(d)), []);
});

test("queue chat keeps strangers but never repeats a tab-identified party member", () => {
  const friend = p("CurrentNick");
  const stranger = p("PublicChatter");
  const d = {
    context: "QUEUE",
    yourParty: [friend],
    players: [friend, stranger],
  };
  assert.deepEqual(withoutPlayers(d.players, dashboardParty(d)), [stranger]);
});

test("self team is pinned and cannot remain among opponent sections", () => {
  const self = p("Me");
  const friend = p("HiddenNick", "PartyFriend");
  const enemy = p("Enemy");
  const own = { name: "Blue", players: [self, friend] };
  const d = {
    context: "GAME",
    self: "me",
    yourParty: [p("PartyFriend")],
    players: [self, friend, enemy],
    teams: [own, { name: "Red", players: [enemy] }],
  };

  assert.equal(ownTeam(d), own);
  assert.deepEqual(dashboardParty(d), [friend, self]);
  assert.deepEqual(withoutPlayers(own.players, dashboardParty(d)), []);
  assert.deepEqual(opponentTeams(d), [{ name: "Red", players: [enemy] }]);
});

test("party overlap finds the own team during brief self-profile churn", () => {
  const friend = p("Nick", "Friend");
  const own = { name: "Green", players: [friend] };
  const d = {
    context: "GAME",
    self: "temporarily_missing",
    yourParty: [p("Friend")],
    players: [friend],
    teams: [own],
  };
  assert.equal(ownTeam(d), own);
});

// ── presence ────────────────────────────────────────────────────────────────

const ok = (name, fkdr, presence) => ({ name, state: "OK", fkdr, ...(presence ? { presence } : {}) });
const loading = (name, presence) => ({ name, state: "LOADING", ...(presence ? { presence } : {}) });
const game = (self, teams) => ({ context: "GAME", self, yourParty: [], teams });
const team = (name, players) => ({ name, players });

test("a missing presence field is an active player (older mod jars)", () => {
  assert.equal(isActive(ok("a", 1)), true);
  assert.equal(isActive(ok("a", 1, "ACTIVE")), true);
  assert.equal(isActive(ok("a", 1, "DISCONNECTED")), false);
  assert.equal(presenceBadge(ok("a", 1)), null);
  assert.equal(presenceBadge(ok("a", 1, "DISCONNECTED")), "DC");
  assert.equal(presenceBadge(ok("a", 1, "ELIMINATED")), "OUT");
  assert.equal(presenceBadge(ok("a", 1, "MISSING")), "GONE");
});

test("team average ignores players who are out and players still resolving", () => {
  assert.equal(teamAvgFkdr([ok("a", 8), ok("b", 4), ok("c", 30, "ELIMINATED"), loading("d")]), 6);
  assert.equal(teamAvgFkdr([ok("c", 30, "DISCONNECTED")]), null);
  assert.equal(teamAvgFkdr([loading("d")]), null);
  assert.equal(teamAvgFkdr([]), null);
});

test("team standing: eliminated when nobody is active, unresolved when nobody resolved", () => {
  assert.equal(teamStanding(team("Red", [ok("a", 8), ok("b", 4, "ELIMINATED")])), "active");
  assert.equal(teamStanding(team("Red", [loading("a"), ok("b", 4, "ELIMINATED")])), "unresolved");
  assert.equal(teamStanding(team("Red", [ok("a", 8, "ELIMINATED"), ok("b", 4, "DISCONNECTED")])), "eliminated");
  assert.equal(teamStanding(team("Red", [ok("a", 8, "MISSING")])), "eliminated");
  assert.equal(teamStanding(team("Red", [])), "eliminated");
  // A team whose only OK player is disconnected has no average and nobody active.
  const dcOnly = team("Blue", [ok("a", 9, "DISCONNECTED")]);
  assert.equal(teamAvgFkdr(dcOnly.players), null);
  assert.equal(teamStanding(dcOnly), "eliminated");
});

test("sortRoster: active OK by FKDR, then active unresolved, then out players in given order", () => {
  const sorted = sortRoster([
    ok("low", 1),
    ok("outHigh", 40, "ELIMINATED"),
    loading("res1"),
    ok("high", 9),
    ok("dc", 5, "DISCONNECTED"),
    { name: "nick", state: "NICKED" },
    ok("gone", 7, "MISSING"),
  ]);
  assert.deepEqual(
    sorted.map((p) => p.name),
    ["high", "low", "res1", "nick", "outHigh", "dc", "gone"],
  );
});

test("rankedOpponentTeams: (i) all averages null → order active-standing first, no Target", () => {
  const d = game("me", [
    team("Blue", [ok("b1", 20, "ELIMINATED")]),
    team("Red", [loading("r1")]),
    team("Green", [ok("me", 3)]),
  ]);
  const ranked = rankedOpponentTeams(d);
  assert.deepEqual(ranked.map((t) => t.team.name), ["Red", "Blue"]);
  assert.deepEqual(ranked.map((t) => t.standing), ["unresolved", "eliminated"]);
  assert.deepEqual(ranked.map((t) => t.agg), [null, null]);
  assert.deepEqual(ranked.map((t) => t.target), [false, false]);
});

test("rankedOpponentTeams: (ii) numeric averages lead, only the top numeric team is Target", () => {
  const d = game("me", [
    team("Yellow", [ok("y1", 2, "ELIMINATED"), ok("y2", 1, "DISCONNECTED")]),
    team("Red", [loading("r1")]),
    team("Green", [ok("g1", 5), ok("g2", 40, "ELIMINATED")]),
    team("Pink", [ok("p1", 8), loading("p2")]),
    team("Blue", [ok("me", 3)]),
  ]);
  const ranked = rankedOpponentTeams(d);
  assert.deepEqual(ranked.map((t) => t.team.name), ["Pink", "Green", "Red", "Yellow"]);
  assert.deepEqual(ranked.map((t) => t.agg), [8, 5, null, null]);
  assert.deepEqual(ranked.map((t) => t.target), [true, false, false, false]);
  assert.deepEqual(ranked.map((t) => t.standing), ["active", "active", "unresolved", "eliminated"]);
  // Rows come back sorted with out players last.
  assert.deepEqual(ranked[1].players.map((p) => p.name), ["g1", "g2"]);
});

test("rankedOpponentTeams: (iii) the local team and party members never appear", () => {
  const d = {
    context: "GAME",
    self: "me",
    yourParty: [{ name: "duo" }],
    teams: [team("Blue", [ok("me", 3), ok("duo", 9)]), team("Red", [ok("r1", 4), ok("duo", 9)])],
  };
  const ranked = rankedOpponentTeams(d);
  assert.deepEqual(ranked.map((t) => t.team.name), ["Red"]);
  assert.deepEqual(ranked[0].players.map((p) => p.name), ["r1"]);
  assert.equal(ranked[0].target, true);
});
