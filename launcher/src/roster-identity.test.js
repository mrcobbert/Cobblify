import test from "node:test";
import assert from "node:assert/strict";

import {
  dashboardParty,
  opponentTeams,
  ownTeam,
  resolvedParty,
  samePlayer,
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
