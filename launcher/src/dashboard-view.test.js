import test from "node:test";
import assert from "node:assert/strict";

import { classifyLive, isValidLobbySnapshot } from "./lobby-validator.js";
import {
  dashboardView,
  isDashboardEligible,
  liveDashboardAction,
  nextLiveDashboard,
  OUTDATED_NOTE,
  UNSUPPORTED_NOTE,
  viewUnsupported,
} from "./dashboard-view.js";

const pp = {
  name: "PartyPal",
  state: "OK",
  nicked: false,
  realName: null,
  rank: "",
  rankCodes: "",
  mode: "Overall",
  fkdr: 1,
  wlr: 1,
  finalKills: 0,
  kd: 1,
  fkdrTier: 0,
  cheater: false,
  badge: null,
  chips: [],
  seraphThreat: -1,
};

/** The v1 row an older mod jar writes (tags as strings, no decisions). */
const pp1 = {
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
    v: 2,
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

test("unsupported view hides everyone and uses the exact note", () => {
  const view = viewUnsupported(liveSnap({ dashboardEligible: false }));
  const html = JSON.stringify(view);
  assert.match(html, new RegExp(UNSUPPORTED_NOTE));
  assert.doesNotMatch(html, /PartyPal/);
  assert.doesNotMatch(html, /SkyWarsRandom/);
  assert.equal(view.bare, true);
  assert.equal(view.title, "");
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

test("poll-shaped first-live ineligible then eligible mutates firstLiveSeen", () => {
  const fallback = (d) => ({
    title: "Main Lobby",
    blocks: [{ rows: (d.players ?? []).map((p) => p.name) }],
  });
  let firstLiveSeen = false;
  const first = nextLiveDashboard(liveSnap({ dashboardEligible: false, seq: 1 }), {
    firstLiveSeen,
    eligibleViewOf: fallback,
  });
  assert.equal(first.enterDashboard, true);
  assert.match(JSON.stringify(first.view), new RegExp(UNSUPPORTED_NOTE));
  firstLiveSeen = first.nextFirstLiveSeen;
  assert.equal(firstLiveSeen, true);
  const second = nextLiveDashboard(liveSnap({ dashboardEligible: true, seq: 2 }), {
    firstLiveSeen,
    eligibleViewOf: fallback,
  });
  assert.equal(second.enterDashboard, false);
  assert.equal(second.view.title, "Main Lobby");
  assert.doesNotMatch(JSON.stringify(second.view), new RegExp(UNSUPPORTED_NOTE));
});

// A10: a well-formed snapshot from an older mod jar shows the update note, never a roster.
test("a v1 snapshot renders the outdated note instead of a roster", () => {
  const legacy = {
    ...liveSnap({ context: "GAME" }),
    v: 1,
    yourParty: [pp1],
    players: [{ ...pp1, name: "OldRow" }],
  };
  assert.ok(isValidLobbySnapshot(legacy));
  assert.equal(classifyLive(legacy), "live");
  let called = false;
  const view = dashboardView(legacy, () => {
    called = true;
    return { title: "roster", sub: "", blocks: [] };
  });
  assert.equal(called, false);
  assert.equal(view.bare, true);
  assert.match(view.blocks[0].foot, new RegExp(OUTDATED_NOTE));
  const step = nextLiveDashboard(legacy, { firstLiveSeen: true, eligibleViewOf: () => ({ blocks: [] }) });
  assert.match(step.view.blocks[0].foot, new RegExp(OUTDATED_NOTE));
  assert.equal(step.eligible, true);
});
