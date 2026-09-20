import test from "node:test";
import assert from "node:assert/strict";

import { createConnectionModel } from "./connection-state.js";
import { createConnectionShell } from "./connection-shell.js";
import { handleLobbyPoll } from "./lobby-poll-handler.js";
import { nextLiveDashboard, UNSUPPORTED_NOTE } from "./dashboard-view.js";

const pp = {
  name: "you_",
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
    players: [],
    teams: [],
    ...extra,
  };
}

function menuSnap(extra = {}) {
  return liveSnap({
    context: "MENU",
    inHypixel: false,
    yourParty: [],
    ...extra,
  });
}

function fakeEl() {
  return {
    textContent: "",
    hidden: true,
    classList: { on: false, toggle(_c, v) { this.on = v; } },
    dataset: {},
    setAttribute() {},
    addEventListener() {},
  };
}

function makeHarness({
  manualRouteSubtitle = "Prism is launching — use Minecraft when ready.",
  homeUntilLive = false,
} = {}) {
  const connection = createConnectionModel({ graceMs: 2000 });
  connection.beginLaunchSession({ autoJoin: true, now: 0 });
  connection.tick("live", 0);

  const titleEl = fakeEl();
  const subEl = fakeEl();
  const shell = createConnectionShell({
    titleEl,
    subEl,
    shellEl: fakeEl(),
    clearRoster: () => {},
    stopProgress: () => {},
    showDash: () => {},
    hideDash: () => {},
    clock: () => 0,
    schedule: (fn, ms) => setTimeout(fn, ms),
    cancel: clearTimeout,
  });

  let applied = 0;
  let resetReasons = [];
  let stopped = false;
  // Recorded, never silent: a stray dashboard open is exactly what the
  // home-until-live routing has to prevent.
  let dashOpens = 0;

  const launchController = {
    launchGen: 1,
    getState() {
      return { launchGen: this.launchGen };
    },
    async acknowledgeSnapshot() {},
  };

  return {
    connection,
    shell,
    subEl,
    async poll(poll, now = 0) {
      await handleLobbyPoll(poll, {
        connection,
        connectionShell: shell,
        launchController,
        manualRouteSubtitle,
        homeUntilLive,
        firstLiveSeen: true,
        applyLobbySnapshot: () => {
          applied += 1;
        },
        resetToHome: (reason) => {
          resetReasons.push(reason);
        },
        stopLobbyPolling: () => {
          stopped = true;
        },
        setStageDash: () => {
          dashOpens += 1;
        },
        now,
      });
    },
    get dashOpens() {
      return dashOpens;
    },
    get applied() {
      return applied;
    },
    get resetReasons() {
      return resetReasons;
    },
    get stopped() {
      return stopped;
    },
  };
}

test("first menu grace keeps connected without applying empty roster", async () => {
  const h = makeHarness();
  await h.poll({ kind: "snapshot", token: 1, snapshot: menuSnap() }, 500);
  assert.equal(h.applied, 0);
  assert.equal(h.connection.stateAt(500).mode, "connected");
});

test("second menu after grace disconnects and clears via shell", async () => {
  const h = makeHarness();
  await h.poll({ kind: "snapshot", token: 1, snapshot: menuSnap() }, 500);
  await h.poll({ kind: "snapshot", token: 2, snapshot: menuSnap({ seq: 2 }) }, 2500);
  assert.equal(h.connection.stateAt(2500).mode, "disconnected");
  assert.equal(h.applied, 0);
});

test("unavailable manual poll preserves route-specific subtitle", async () => {
  const subtitle = "Lunar is open — press Play in Lunar when ready.";
  const h = makeHarness({ manualRouteSubtitle: subtitle });
  h.connection.setAutoJoin(false);
  h.connection.beginLaunchSession({ autoJoin: false, now: 0 });
  await h.poll({ kind: "unavailable", reason: "stale_mtime" }, 0);
  assert.equal(h.subEl.textContent, subtitle);
});

test("session ended drives exactly one home reset and nothing else", async () => {
  const h = makeHarness();
  await h.poll({ kind: "session_ended", reason: "game_session_changed" }, 100);
  assert.deepEqual(h.resetReasons, ["game_session_changed"]);
  // The orchestrator owns stopping/invalidating; the handler adds nothing.
  assert.equal(h.stopped, false);
  assert.equal(h.applied, 0);
  await h.poll({ kind: "session_ended", reason: "game_session_ended" }, 200);
  assert.deepEqual(h.resetReasons, ["game_session_changed", "game_session_ended"]);
});

test("live snapshot applies roster while connected", async () => {
  const h = makeHarness();
  await h.poll({ kind: "snapshot", token: 1, snapshot: liveSnap({ seq: 2 }) }, 100);
  assert.equal(h.applied, 1);
});

test("direct-launch route (null subtitle) stays on the home page", async () => {
  const h = makeHarness({ manualRouteSubtitle: null });
  h.connection.setAutoJoin(false);
  h.connection.beginLaunchSession({ autoJoin: false, now: 0 });
  await h.poll({ kind: "unavailable", reason: "stale_mtime" }, 0);
  // No manual screen, no dash: the home buttons narrate progress instead.
  assert.equal(h.shell.getVisible(), false);
  assert.equal(h.dashOpens, 0);
});

test("auto-join-on unavailable poll holds the home view until live", async () => {
  const h = makeHarness({ homeUntilLive: true });
  h.connection.beginLaunchSession({ autoJoin: true, now: 0 });
  assert.equal(h.connection.stateAt(0).mode, "joining");
  await h.poll({ kind: "unavailable", reason: "stale_mtime" }, 0);
  assert.equal(h.shell.getVisible(), false);
  assert.equal(h.dashOpens, 0);
});

test("auto-join-on non-live snapshot holds the home view until live", async () => {
  const h = makeHarness({ homeUntilLive: true });
  h.connection.beginLaunchSession({ autoJoin: true, now: 0 });
  await h.poll({ kind: "snapshot", token: 1, snapshot: menuSnap() }, 100);
  assert.equal(h.shell.getVisible(), false);
  assert.equal(h.dashOpens, 0);
  assert.equal(h.applied, 0);
});

test("the live snapshot is the one dashboard entry while home-until-live", async () => {
  const h = makeHarness({ homeUntilLive: true });
  h.connection.beginLaunchSession({ autoJoin: true, now: 0 });
  await h.poll({ kind: "snapshot", token: 1, snapshot: liveSnap({ seq: 3 }) }, 100);
  // applyLobbySnapshot owns the transition (enterDashboard); the handler
  // never opens the dash itself.
  assert.equal(h.applied, 1);
  assert.equal(h.dashOpens, 0);
});

test("the poll path never opens the waiting dash while home-until-live", async () => {
  // Review fix: connection-state arms its internal waiting deadline for
  // EVERY auto-join session, including the headless overlay-off route. Only
  // the explicit 60s deadline (armed solely for overlay-on) may open the
  // waiting dash; this poll path must stay silent.
  const h = makeHarness({ homeUntilLive: true });
  h.connection.beginLaunchSession({ autoJoin: true, now: 0 });
  await h.poll({ kind: "unavailable", reason: "stale_mtime" }, 61_000);
  assert.equal(h.connection.stateAt(61_000).mode, "waiting");
  assert.equal(h.shell.getVisible(), false);
  assert.equal(h.dashOpens, 0);
});

test("without the marker the waiting poll path keeps its dash behavior", async () => {
  const h = makeHarness({ homeUntilLive: false });
  h.connection.beginLaunchSession({ autoJoin: true, now: 0 });
  await h.poll({ kind: "unavailable", reason: "stale_mtime" }, 61_000);
  assert.equal(h.shell.getVisible(), true);
  assert.equal(h.dashOpens, 1);
});

test("first live ineligible poll enters, later eligible poll restores the roster", async () => {
  const connection = createConnectionModel();
  connection.beginLaunchSession({ autoJoin: true, now: 0 });
  const shell = createConnectionShell({
    root: { hidden: true, dataset: {} },
    titleEl: {},
    subEl: {},
    actionSlot: { innerHTML: "", hidden: true },
    dash: { innerHTML: "" },
    announce: {},
  });
  let firstLiveSeen = false;
  const steps = [];
  const fallback = (d) => ({
    title: "Main Lobby",
    blocks: [{ rows: (d.players ?? []).map((p) => p.name) }],
  });
  const applyLobbySnapshot = (snapshot, { reconnect = false } = {}) => {
    const step = nextLiveDashboard(snapshot, {
      firstLiveSeen,
      wasDisconnected: reconnect,
      eligibleViewOf: fallback,
    });
    if (step.enterDashboard) firstLiveSeen = step.nextFirstLiveSeen;
    steps.push(step);
  };
  const deps = {
    connection,
    connectionShell: shell,
    launchController: {
      launchGen: 1,
      getState() {
        return { launchGen: 1 };
      },
      async acknowledgeSnapshot() {},
    },
    manualRouteSubtitle: null,
    homeUntilLive: true,
    get firstLiveSeen() {
      return firstLiveSeen;
    },
    applyLobbySnapshot,
    resetToHome: () => {},
    stopLobbyPolling: () => {},
    setStageDash: () => {},
    now: 100,
  };
  const stranger = { ...pp, name: "LobbySteve" };
  await handleLobbyPoll(
    {
      kind: "snapshot",
      token: 1,
      snapshot: liveSnap({ dashboardEligible: false, seq: 1, players: [stranger] }),
    },
    deps,
  );
  assert.equal(firstLiveSeen, true);
  assert.equal(steps[0].enterDashboard, true);
  assert.match(JSON.stringify(steps[0].view), new RegExp(UNSUPPORTED_NOTE));
  deps.now = 200;
  await handleLobbyPoll(
    {
      kind: "snapshot",
      token: 2,
      snapshot: liveSnap({ dashboardEligible: true, seq: 2, players: [stranger] }),
    },
    deps,
  );
  assert.equal(steps[1].enterDashboard, false);
  assert.equal(steps[1].view.title, "Main Lobby");
  assert.deepEqual(steps[1].view.blocks[0].rows, ["LobbySteve"]);
});
