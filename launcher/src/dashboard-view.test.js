import test from "node:test";
import assert from "node:assert/strict";

import { classifyLive, isValidLobbySnapshot } from "./lobby-validator.js";
import {
  dashboardView,
  isDashboardEligible,
  liveDashboardAction,
  UNSUPPORTED_NOTE,
  viewUnsupported,
} from "./dashboard-view.js";

const pp = {
  name: "PartyPal",
  state: "OK",
  nicked: false,
  realName: null,
  rank: "",
  fkdr: 1,
  wlr: 1,
  finalKills: 0,
  kd: 1,
  seraphThreat: -1,
  seraphTags: [],
  urchinTags: [],
};

const stranger = { ...pp, name: "SkyWarsRandom" };

function liveSnap(extra = {}) {
  return {
    v: 1,
    seq: 1,
    jvmPid: 42,
    jvmStartTimeMs: 1_700_000_000_000,
    context: "LOBBY",
    inHypixel: true,
    self: "you_",
    mode: null,
    partyCount: null,
    yourParty: [pp],
    players: [stranger],
    teams: [],
    ...extra,
  };
}

test("absent dashboardEligible stays valid and live", () => {
  const snap = liveSnap();
  assert.ok(isValidLobbySnapshot(snap));
  assert.equal(classifyLive(snap), "live");
  assert.equal(isDashboardEligible(snap), true);
});

test("explicit false is still live for session binding", () => {
  const snap = liveSnap({ dashboardEligible: false });
  assert.ok(isValidLobbySnapshot(snap));
  assert.equal(classifyLive(snap), "live");
  assert.equal(isDashboardEligible(snap), false);
});

test("non-boolean dashboardEligible is rejected", () => {
  assert.ok(!isValidLobbySnapshot(liveSnap({ dashboardEligible: "yes" })));
});

test("first live ineligible snapshot still enters the dashboard", () => {
  const snap = liveSnap({ dashboardEligible: false });
  const first = liveDashboardAction(snap, { firstLiveSeen: false });
  assert.equal(first.enterDashboard, true);
  assert.equal(first.eligible, false);
  const later = liveDashboardAction(snap, { firstLiveSeen: true });
  assert.equal(later.enterDashboard, false);
});

test("unsupported view keeps party, hides others, and uses the exact note", () => {
  const view = viewUnsupported(liveSnap({ dashboardEligible: false }));
  const html = JSON.stringify(view);
  assert.match(html, new RegExp(UNSUPPORTED_NOTE));
  assert.match(html, /PartyPal/);
  assert.doesNotMatch(html, /SkyWarsRandom/);
  assert.equal(view.title, "Bed Wars");
});

test("ineligible then eligible restores the roster view without a relaunch", () => {
  const fallback = (d) => ({
    title: "Main Lobby",
    sub: `· ${(d.players ?? []).length} players`,
    blocks: [{ rows: (d.players ?? []).map((p) => p.name) }],
  });
  const hidden = dashboardView(liveSnap({ dashboardEligible: false, seq: 1 }), fallback);
  assert.match(JSON.stringify(hidden), new RegExp(UNSUPPORTED_NOTE));
  assert.doesNotMatch(JSON.stringify(hidden), /SkyWarsRandom/);

  const shown = dashboardView(
    liveSnap({ dashboardEligible: true, seq: 2 }),
    fallback,
  );
  assert.equal(shown.title, "Main Lobby");
  assert.deepEqual(shown.blocks[0].rows, ["SkyWarsRandom"]);
  assert.doesNotMatch(JSON.stringify(shown), new RegExp(UNSUPPORTED_NOTE));
});
