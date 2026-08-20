import test from "node:test";
import assert from "node:assert/strict";

import { createConnectionModel } from "./connection-state.js";
import { createConnectionShell } from "./connection-shell.js";
import { handleLobbyPoll } from "./lobby-poll-handler.js";

const pp = {
  name: "you_",
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

function makeHarness({ manualRouteSubtitle = "Prism is launching — use Minecraft when ready." } = {}) {
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
  let terminal = null;
  let stopped = false;

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
        firstLiveSeen: true,
        applyLobbySnapshot: () => {
          applied += 1;
        },
        enterTerminal: (reason) => {
          terminal = reason;
          shell.enterTerminal(
            reason === "game_session_changed" ? "session_changed" : "session_ended",
          );
        },
        stopLobbyPolling: () => {
          stopped = true;
        },
        setStageDash: () => {},
        now,
      });
    },
    get applied() {
      return applied;
    },
    get terminal() {
      return terminal;
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

test("session ended stops polling and enters terminal", async () => {
  const h = makeHarness();
  await h.poll({ kind: "session_ended", reason: "game_session_changed" }, 100);
  assert.equal(h.stopped, true);
  assert.equal(h.terminal, "game_session_changed");
  assert.equal(h.connection.stateAt(100).mode, "session_changed");
});

test("live snapshot applies roster while connected", async () => {
  const h = makeHarness();
  await h.poll({ kind: "snapshot", token: 1, snapshot: liveSnap({ seq: 2 }) }, 100);
  assert.equal(h.applied, 1);
});
