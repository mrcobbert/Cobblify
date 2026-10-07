import "@fontsource/unbounded/latin-700.css";
import "@fontsource/dm-mono/latin-400.css";
import "@fontsource/dm-mono/latin-500.css";
import "./ui/fonts.css";
import "./ui/tokens.css";
import "./dash.css";
import "./ui/app.css";

import {
  activeFirst,
  aliases,
  dashboardParty,
  isActive,
  presenceBadge,
  rankedOpponentTeams,
  sortRoster,
  withoutPlayers,
} from "./roster-identity.js";
import { PREVIEW_STATUS, resolvePreviewStatus } from "./setup-fixtures.js";
import { PREVIEW_UPDATE, resolvePreviewUpdate } from "./update-fixtures.js";
import { createConnectionModel } from "./connection-state.js";
import { createConnectionShell } from "./connection-shell.js";
import { createLaunchController } from "./launch-controller.js";
import { createOverlayExit } from "./overlay-exit.js";
import { createSessionReset } from "./session-reset.js";
import { createUpdateController } from "./update-controller.js";
import { handleLobbyPoll as runLobbyPoll } from "./lobby-poll-handler.js";
import { nextLiveDashboard } from "./dashboard-view.js";
import { colorClass, rowModel, sectionSpans } from "./row-model.js";
import {
  abortLaunchSession,
  gameTheme,
  openInfo,
  quitApp,
  rehideAfterConfirmation,
  resetSessionEnd,
} from "./tauri-contract.js";
import { LOBBY_POLL_MS } from "./poll-config.js";
import { readyTargets } from "./setup-view.js";
import { manualRouteSubtitleFor } from "./connection-view.js";
import { launchViewRoute, showsCancelLaunch } from "./launch-view-route.js";
import { createApp } from "./ui/app.js";
import { CL } from "./ui/art.js";
import { NEW_PLAYER_THEME, themeFor } from "./ui/accent.js";
import { setAccent, setFont, watchUnits, wordmark } from "./ui/kit.js";
import { homeModel, sheetModel } from "./ui/model.js";

const el = (id) => document.getElementById(id);
const stage = el("stage");
const joining = el("joining");
const dash = el("dash");
const connAnnounce = el("conn-announce");

/**
 * The Tauri bridge is injected by the app shell (withGlobalTauri). Outside it -
 * `npm run dev` in a browser - fall back to a stub so the whole flow can be
 * watched without touching the real Lunar config or the mod's lobby.json.
 *   ?state=lunarReady|prismChoose|bothReady|…  picks the initial setup case
 *   ?update=consent|checking|available|critical|downloading|…  updater state
 *   ?ctx=lobby|unsupported|joining|waiting|disconnected  lobby dashboard / joining / waiting
 *   ?ctx=preexisting_game|session_ended|session_changed|launch_aborted
 *   ?ctx=queueSolo|queueDoubles|queueThrees|queueFours
 *   ?ctx=gameSolo|gameDoubles|gameThrees|gameFours|game4v4
 *   ?open=updated|fresh&version=…  what open_info answers
 *   ?accent=<token>&font=minecraft|modern  what game_theme answers
 */
const invoke = window.__TAURI__?.core?.invoke ?? previewInvoke;

// ── preview sample data ─────────────────────────────────────────────────────
// Shaped exactly like a v2 lobby.json row: the mod has already decided the
// tier, the cheater flag, the badge and the chips, so the sample carries them.
const RANK_CODES = {
  "[MVP++]": "§6[MVP§c++§6]",
  "[MVP+]": "§b[MVP§c+§b]",
  "[MVP]": "§b[MVP]",
  "[VIP+]": "§a[VIP§6+§a]",
  "[VIP]": "§a[VIP]",
};
const chip = (code, color, label, positive = false) => ({ code, color, label, positive });
const tagged = (chips, cheater) => ({ chips, badge: chips[0] ?? null, cheater });
const pp = (o) =>
  Object.assign(
    {
      state: "OK",
      nicked: false,
      realName: null,
      rank: "",
      rankCodes: RANK_CODES[o.rank] ?? "",
      mode: "Overall",
      fkdr: 0,
      wlr: 0,
      finalKills: 0,
      kd: 0,
      fkdrTier: 0,
      cheater: false,
      badge: null,
      chips: [],
      seraphThreat: -1,
    },
    o,
  );

const P = {
  you: pp({ name: "you_", rank: "[MVP+]", fkdrTier: 2, fkdr: 5.9, wlr: 3.8, finalKills: 15200, kd: 3.2 }),
  duo: pp({ name: "duo_diff", rank: "[VIP+]", fkdrTier: 2, fkdr: 7.2, wlr: 4.9, finalKills: 19000, kd: 3.9 }),
  third: pp({ name: "third_wheeler", rank: "[MVP]", fkdrTier: 1, fkdr: 3.1, wlr: 2.2, finalKills: 6400, kd: 2.4 }),
  clutch: pp({ name: "clutch_or_kick", rank: "[VIP]", fkdrTier: 0, fkdr: 1.8, wlr: 1.1, finalKills: 2100, kd: 1.4 }),
  shadow: pp({ name: "xXShadowSlayerXx", rank: "[MVP++]", fkdrTier: 3, fkdr: 22.7, wlr: 14.0, finalKills: 71200, kd: 8.9, seraphThreat: 4, ...tagged([chip("BC", "§6", "Blatant Cheater"), chip("BL", "§4", "Blacklisted (verified)")], true) }),
  wallhax: pp({ name: "wallhax_", rank: "[VIP]", fkdrTier: 3, fkdr: 19.1, wlr: 11.4, finalKills: 60300, kd: 7.7, ...tagged([chip("CC", "§6", "Closet Cheater")], true) }),
  tenko: pp({ name: "Tenko", rank: "[MVP++]", fkdrTier: 3, fkdr: 14.2, wlr: 9.1, finalKills: 48210, kd: 6.7, seraphThreat: 2 }),
  zenith: pp({ name: "ok_zenith", rank: "[MVP+]", fkdrTier: 2, fkdr: 8.4, wlr: 5.2, finalKills: 22140, kd: 4.1 }),
  prot: pp({ name: "Prot_IV_Diamond", rank: "[MVP+]", fkdrTier: 2, fkdr: 6.8, wlr: 4.4, finalKills: 17700, kd: 3.6 }),
  frost: pp({ name: "GodBridgeGod", nicked: true, realName: "Frostbyte_", rank: "[MVP]", fkdrTier: 1, fkdr: 4.6, wlr: 3.1, finalKills: 9800, kd: 2.8 }),
  moss: pp({ name: "mossling", rank: "[MVP]", fkdrTier: 1, fkdr: 3.3, wlr: 2.0, finalKills: 5600, kd: 2.2 }),
  ogre: pp({ name: "TheObsidianOgre", rank: "[MVP]", fkdrTier: 1, fkdr: 2.9, wlr: 1.8, finalKills: 4900, kd: 2.0 }),
  aqua: pp({ name: "aqua_gg", rank: "[VIP+]", fkdrTier: 1, fkdr: 2.1, ...tagged([chip("SL", "§a", "Safelisted", true)], false), wlr: 1.4, finalKills: 3120, kd: 1.6 }),
  virus: pp({ name: "notavirus_exe", rank: "[VIP+]", fkdrTier: 0, fkdr: 1.6, wlr: 1.2, finalKills: 2440, kd: 1.3, ...tagged([chip("S", "§4", "Sniper")], false) }),
  bread: pp({ name: "Bread_Enjoyer", rank: "[VIP]", fkdrTier: 0, fkdr: 1.05, wlr: 0.9, finalKills: 880, kd: 1.1 }),
  cool: pp({ name: "coolkid2013", rank: "", fkdrTier: 0, fkdr: 0.42, wlr: 0.5, finalKills: 120, kd: 0.7 }),
  nick: pp({ name: "iToxicWaffle", rank: "", state: "NICKED", nicked: true }),
  grandpa: pp({ name: "Grandpa_Joe", rank: "", state: "NEVER_PLAYED" }),
  lag: pp({ name: "lagswitch99", rank: "[VIP]", state: "LOADING" }),
  sniped: pp({ name: "sniped_u", rank: "", state: "LOADING" }),
};

/** Preview only: the same player, out of the game. */
const out = (p, presence) => ({ ...p, presence });

const PREVIEW_PARTY1 = [P.you];
const PREVIEW_PARTY = [P.you, P.duo];
const PREVIEW_PARTY3 = [P.you, P.duo, P.third];
const PREVIEW_PARTY4 = [P.you, P.duo, P.third, P.clutch];

function previewSnap(context, extra) {
  return Object.assign(
    {
      v: 1,
      jvmPid: 4242,
      jvmStartTimeMs: 1_700_000_000_000,
      inHypixel: true,
      self: "you_",
      mode: null,
      partyCount: null,
      yourParty: [],
      teams: [],
      players: [],
    },
    extra,
    { context },
  );
}

const team = (name, players) => ({ name, players });

const PREVIEW_LOBBY = {
  lobby: previewSnap("LOBBY", {
    seq: 1001,
    yourParty: PREVIEW_PARTY,
    players: [P.shadow, P.tenko, P.zenith, P.prot, P.frost, P.moss, P.aqua, P.bread, P.cool, P.nick, P.grandpa, P.lag],
  }),
  unsupported: previewSnap("LOBBY", { seq: 1002, dashboardEligible: false }),
  queueSolo: previewSnap("QUEUE", {
    seq: 1010,
    mode: "Solos",
    partyCount: 8,
    yourParty: PREVIEW_PARTY1,
    players: [P.wallhax, P.tenko, P.sniped],
  }),
  queueDoubles: previewSnap("QUEUE", {
    seq: 1011,
    mode: "Doubles",
    partyCount: 5,
    yourParty: PREVIEW_PARTY,
    players: [P.wallhax, P.tenko, P.zenith, P.frost, P.sniped],
  }),
  queueThrees: previewSnap("QUEUE", {
    seq: 1012,
    mode: "3v3v3v3",
    partyCount: 4,
    yourParty: PREVIEW_PARTY3,
    players: [P.shadow, P.wallhax, P.tenko, P.zenith, P.frost, P.moss, P.nick, P.lag],
  }),
  queueFours: previewSnap("QUEUE", {
    seq: 1013,
    mode: "4v4v4v4",
    partyCount: 6,
    yourParty: PREVIEW_PARTY4,
    players: [P.shadow, P.wallhax, P.tenko, P.zenith, P.prot, P.frost, P.moss, P.ogre, P.aqua, P.virus, P.bread, P.cool, P.nick, P.grandpa, P.lag, P.sniped],
  }),
  gameSolo: previewSnap("GAME", {
    seq: 1020,
    mode: "Solos",
    yourParty: PREVIEW_PARTY1,
    teams: [
      team("Red", [P.shadow]),
      team("Blue", [P.you]),
      team("Green", [P.tenko]),
      team("Yellow", [P.zenith]),
      team("Aqua", [P.moss]),
      team("White", [P.bread]),
      team("Pink", [P.nick]),
      team("Gray", [P.grandpa]),
    ],
  }),
  gameDoubles: previewSnap("GAME", {
    seq: 1021,
    mode: "Doubles",
    yourParty: PREVIEW_PARTY,
    teams: [
      team("Red", [P.shadow, P.wallhax]),
      team("Blue", [P.you, P.duo]),
      team("Green", [P.tenko, P.zenith]),
      team("Yellow", [P.prot, P.frost]),
      team("Aqua", [P.moss, P.aqua]),
      team("White", [P.bread, P.cool]),
      team("Pink", [P.virus, P.nick]),
      team("Gray", [P.grandpa, P.lag]),
    ],
  }),
  gameThrees: previewSnap("GAME", {
    seq: 1022,
    mode: "3v3v3v3",
    yourParty: PREVIEW_PARTY,
    teams: [
      team("Red", [P.shadow, P.tenko, P.zenith]),
      team("Blue", [P.you, P.duo, P.bread]),
      team("Green", [P.moss, P.cool, P.grandpa]),
      team("Yellow", [P.aqua, P.nick, P.prot]),
    ],
  }),
  gameFours: previewSnap("GAME", {
    seq: 1023,
    mode: "4v4v4v4",
    yourParty: PREVIEW_PARTY4,
    teams: [
      team("Red", [P.shadow, P.wallhax, P.tenko, out(P.zenith, "ELIMINATED")]),
      team("Blue", [P.you, P.duo, P.third, P.clutch]),
      team("Green", [P.frost, P.moss, out(P.cool, "DISCONNECTED"), out(P.grandpa, "MISSING")]),
      team("Yellow", [
        out(P.virus, "ELIMINATED"),
        out(P.bread, "ELIMINATED"),
        out(P.nick, "ELIMINATED"),
        out(P.prot, "ELIMINATED"),
      ]),
    ],
  }),
  game4v4: previewSnap("GAME", {
    seq: 1024,
    mode: "4v4",
    yourParty: PREVIEW_PARTY4,
    teams: [
      team("Red", [P.shadow, P.wallhax, P.tenko, P.zenith]),
      team("Blue", [P.you, P.duo, P.third, P.clutch]),
    ],
  }),
};
PREVIEW_LOBBY.queue = PREVIEW_LOBBY.queueDoubles;
PREVIEW_LOBBY.queue16 = PREVIEW_LOBBY.queueFours;
PREVIEW_LOBBY.game = PREVIEW_LOBBY.gameThrees;
PREVIEW_LOBBY.game16 = PREVIEW_LOBBY.gameFours;

let previewLaunchAt = null;
let previewUpdateKey = "consent";

function previewInvoke(command, args) {
  if (command === "status" || command === "refresh_setup") {
    const which = new URLSearchParams(location.search).get("state");
    return new Promise((resolve) =>
      setTimeout(() => resolve(resolvePreviewStatus(which)), 250),
    );
  }

  if (command === "get_launcher") {
    const kind = args?.kind ?? "lunar";
    return Promise.resolve({
      installed: kind === "forge" ? !PREVIEW_STATUS.prismNoInstances : false,
      download_url:
        kind === "forge"
          ? "https://prismlauncher.org/download/"
          : "https://www.lunarclient.com/download",
    });
  }

  if (command === "open_launcher" || command === "open_setup_location") {
    return Promise.resolve();
  }

  if (command === "choose_forge_target") {
    return Promise.resolve(PREVIEW_STATUS.bothReady);
  }

  if (command === "launch_preferences") {
    return Promise.resolve({
      autoJoinHypixel: true,
      useExternalOverlay: true,
      health: "missing",
    });
  }

  if (command === "set_auto_join_hypixel") {
    return Promise.resolve({
      status: "saved",
      autoJoinHypixel: args?.enabled ?? true,
      useExternalOverlay: true,
    });
  }

  if (command === "set_use_external_overlay") {
    return Promise.resolve({
      status: "saved",
      autoJoinHypixel: true,
      useExternalOverlay: args?.enabled ?? true,
    });
  }

  if (command === "update_preferences") {
    const snap = resolvePreviewUpdate(previewUpdateKey);
    return Promise.resolve({
      autoUpdateEnabled: snap.autoUpdateEnabled,
      autoUpdatePrompted: snap.autoUpdatePrompted,
      updateChannel: snap.updateChannel,
      health: "valid",
    });
  }

  if (command === "set_update_channel") {
    // Ticking the box in the browser preview shows a waiting dev build; unticking returns to stable.
    previewUpdateKey = args?.channel === "dev" ? "availableDev" : "current";
    const snap = resolvePreviewUpdate(previewUpdateKey);
    return Promise.resolve({
      status: "saved",
      autoUpdateEnabled: snap.autoUpdateEnabled,
      autoUpdatePrompted: snap.autoUpdatePrompted,
      updateChannel: snap.updateChannel,
    });
  }

  if (command === "update_status" || command === "check_for_update") {
    return Promise.resolve(resolvePreviewUpdate(previewUpdateKey));
  }

  if (command === "set_auto_update") {
    return Promise.resolve({
      status: "saved",
      autoUpdateEnabled: args?.enabled ?? false,
      autoUpdatePrompted: true,
      updateChannel: resolvePreviewUpdate(previewUpdateKey).updateChannel,
    });
  }

  if (["start_update", "pause_update", "resume_update", "install_update", "defer_update"].includes(command)) {
    return Promise.resolve(resolvePreviewUpdate(previewUpdateKey));
  }

  // The forcing abort answers in the same shape as the ordinary reset.
  if (command === "reset_session_end" || command === "abort_launch_session") {
    return Promise.resolve({
      status: "ok",
      preferences: { autoJoinHypixel: true, useExternalOverlay: true, health: "valid" },
    });
  }

  if (command === "rehide_after_confirmation" || command === "quit_app") {
    // Preview never exits the page; quit is a no-op here.
    return Promise.resolve();
  }

  if (command === "launch_lunar" || command === "launch_forge") {
    previewLaunchAt = Date.now();
    const autoJoin = args?.expectedAutoJoinHypixel ?? true;
    const overlay = args?.expectedUseExternalOverlay ?? true;
    if (!autoJoin) {
      return Promise.resolve({
        status: "launched",
        outcome: {
          autoJoinHypixel: false,
          useExternalOverlay: overlay,
          action: "game_launch_requested",
        },
      });
    }
    return Promise.resolve({
      status: "launched",
      outcome: {
        autoJoinHypixel: true,
        useExternalOverlay: overlay,
        action: "game_launch_requested",
      },
    });
  }

  if (command === "open_info") {
    // ?open=updated plays the first-open-after-update replay and What's New.
    const q = new URLSearchParams(location.search);
    return Promise.resolve({
      version: q.get("version") || "0.18.0-next.1",
      updated: q.get("open") === "updated",
      fresh: q.get("open") === "fresh",
    });
  }

  if (command === "game_theme") {
    // ?accent=deep-teal&font=modern stand in for the game's cobblify.json.
    const q = new URLSearchParams(location.search);
    const reply = {};
    if (q.get("accent")) reply.accent = q.get("accent");
    if (q.get("font")) reply.font = q.get("font");
    return Promise.resolve(reply);
  }

  if (command === "acknowledge_lobby_snapshot") {
    return Promise.resolve();
  }

  if (command === "launch_progress") {
    const t = previewLaunchAt ? Date.now() - previewLaunchAt : 0;
    const steps = [
      [3000, "settled", 100],
      [2300, "mixing", 85],
      [1600, "discovered", 60],
      [900, "attached", 35],
    ];
    for (const [ms, s, percent] of steps) {
      if (t >= ms) return Promise.resolve({ stage: s, percent });
    }
    return Promise.resolve({ stage: "fired", percent: 10 });
  }

  if (command === "lobby_state") {
    const which = new URLSearchParams(location.search).get("ctx");
    if (which && PREVIEW_LOBBY[which]) {
      return Promise.resolve({
        kind: "snapshot",
        snapshot: PREVIEW_LOBBY[which],
        token: 1,
      });
    }
    const t = previewLaunchAt ? Date.now() - previewLaunchAt : 0;
    if (t < 6000) {
      return Promise.resolve({ kind: "unavailable" });
    }
    return Promise.resolve({
      kind: "snapshot",
      snapshot: PREVIEW_LOBBY.lobby,
      token: 2,
    });
  }

  return Promise.resolve();
}

// ── the 0.18 home (ui/app.js), fed from the backend ─────────────────────────
// The theme before anything answers: a brand-new player's (decision 8).
watchUnits();
setAccent(NEW_PLAYER_THEME.accent);
setFont(NEW_PLAYER_THEME.font);

const CLIENT_KEY = "cobblify.client";
function readStoredClient() {
  try {
    return window.localStorage?.getItem(CLIENT_KEY) ?? null;
  } catch {
    return null;
  }
}
function storeClient(id) {
  try {
    window.localStorage?.setItem(CLIENT_KEY, id);
  } catch {
    // a remembered client is a convenience: without storage the first ready client is used
  }
}

let lastStatus = { state: "loading", targets: [] };
let setupBusy = false;
let refreshTimer = null;
let suppressRefresh = false;
/** The remembered client (decision 5). */
let selected = readStoredClient();
/** Prism instance picked in the interim choose list, and the client being added from the menu. */
let picked = null;
let adding = null;
/** The latest backend launch stage (progress.rs), shown as the Launch key's phase. */
let progressStage = null;
/** The dev switch's value while its save is in flight. */
let devPending = null;
/** open_info: the running version, and whether this is the first open after an update. */
let openInfoReply = null;
let openPlayed = false;
let themeKind = null;

const ui = createApp(el("app"), {
  play: (id) => startLaunch(CL[id]?.kind ?? "lunar"),
  cancel: () => sessionReset.resetToHome("launch_aborted"),
  select: (id) => {
    selected = id;
    storeClient(id);
    refreshUi();
  },
  add: (desc) => {
    if (!desc) return;
    if (desc.act === "get") runSetupAction({ kind: "download", launcher: CL[desc.id].kind });
    else if (desc.act === "add") {
      adding = desc.id;
      picked = null;
      refreshUi();
    } else if (desc.act === "setup") runSetupAction(desc.action);
  },
  setup: (action) => runSetupAction(action),
  pick: (id) => {
    picked = id;
    refreshUi();
  },
  cancelAdd: () => {
    adding = null;
    picked = null;
    refreshUi();
  },
  repairPref: () => {
    // Any save rewrites a valid two-key document (backend salvages the valid
    // sibling); an uncertain save must be repaired through its own key.
    const st = launchController.getState();
    if (st.prefUncertainKey === "overlay") launchController.setOverlay(st.optimisticOverlay, { repair: true });
    else launchController.setAutoJoin(st.optimisticAutoJoin, { repair: true });
  },
  update: (action) => updateController.action(action),
  setOverlay: (on) => launchController.setOverlay(on),
  setAutoUpdate: (on) => updateController.action(on ? "enable" : "disable"),
  setDev: (on) => {
    devPending = on;
    refreshUi();
    Promise.resolve(updateController.setUpdateChannel(on ? "dev" : "stable")).finally(() => {
      devPending = null;
      refreshUi();
    });
  },
  // A refresh re-runs setup, which reinstalls the jars and re-registers launcher.json.
  repairLunar: () => runSetupAction({ kind: "refresh" }),
});

// The home window and the post-launch dashboard take turns: stage.dataset.view
// is the one switch every launch and reset path already sets.
new window.MutationObserver(() => ui.setActive(stage.dataset.view !== "dash")).observe(stage, {
  attributes: true,
  attributeFilter: ["data-view"],
});
stage.dataset.platform = el("app").dataset.platform;
function drawDashMark() {
  const k = Math.max(1, Math.round((parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--su")) || 2) * (window.devicePixelRatio || 1)));
  el("dash-wm").innerHTML = wordmark(k, { font: document.documentElement.dataset.font }, window.devicePixelRatio || 1);
}
document.addEventListener("cobblify:units", drawDashMark);
document.addEventListener("cobblify:theme", drawDashMark);
drawDashMark();

function launchView() {
  const st = launchController.getState();
  return {
    kind: st.launchKind,
    phase: st.launchPhase,
    busy: st.launchBusy,
    stage: progressStage,
    autoJoin: st.activeOutcome?.autoJoinHypixel ?? st.confirmedAutoJoin,
    prefError: st.prefError,
    prefHealth: st.prefHealth,
    prefUncertain: st.prefUncertain,
    active: st.activeOutcome != null,
    showCancel: showsCancelLaunch({
      activeOutcome: st.activeOutcome,
      launchPhase: st.launchPhase,
      homeUntilLive,
      resetActive: sessionReset.isActive(),
    }),
  };
}

function refreshUi() {
  const st = launchController.getState();
  const launch = launchView();
  const update = updateController.getState();
  const version = openInfoReply?.version || update.currentVersion || "";
  const home = homeModel({ status: lastStatus, selected, picked, adding, launch, update });
  if (adding && home.adding !== adding) {
    adding = null;
    picked = null;
  }
  const sheet = sheetModel({
    status: lastStatus,
    launch,
    update,
    overlayOn: st.optimisticOverlay,
    overlaySaving: st.prefSaving,
    version,
    devPending,
  });
  ui.update({ home, sheet, version, dev: update.updateChannel === "dev" });
  applyTheme(home.sel);
  maybeOpen(home);
}

/** The launcher wears the accent and font of the client you play (decision 8). */
function applyTheme(sel) {
  const kind = sel ? CL[sel].kind : "lunar";
  if (kind === themeKind) return;
  themeKind = kind;
  gameTheme(invoke, kind)
    .then((reply) => {
      if (themeKind !== kind) return;
      const t = themeFor(reply);
      setAccent(t.accent);
      setFont(t.font);
    })
    .catch(() => {});
}

/** The first open after an update: the island's build replay, then What's New. Once, on the first real home. */
function maybeOpen(home) {
  if (openPlayed || !openInfoReply || lastStatus.state === "loading" || home.launching) return;
  openPlayed = true;
  if (openInfoReply.updated) ui.open(openInfoReply);
}

function setStatus(status) {
  lastStatus = status;
  // A fresh setup read also re-reads the game's theme: the player may have changed it in game meanwhile.
  themeKind = null;
  refreshUi();
}

async function runSetupAction(action) {
  const st = launchController.getState();
  if (setupBusy || st.activeOutcome != null || st.launchBusy || st.prefSaving || st.prefUncertain) {
    return;
  }
  setupBusy = true;
  try {
    if (action.kind === "refresh") {
      const next = await invoke("refresh_setup");
      if (next) setStatus(next);
      return;
    }
    if (action.kind === "download") {
      await invoke("get_launcher", { kind: action.launcher });
      return;
    }
    if (action.kind === "open_launcher") {
      await invoke("open_launcher", { kind: action.launcher });
      return;
    }
    if (action.kind === "open_folder") {
      await invoke("open_setup_location", { kind: action.launcher });
      return;
    }
    if (action.kind === "setup_prism") {
      const next = await invoke("choose_forge_target", { id: action.candidateId });
      adding = null;
      picked = null;
      if (next) setStatus(next);
    }
  } catch (e) {
    if (action.kind === "refresh") setStatus(setupErrorStatus(e));
    else console.warn("setup action failed", action.kind, e);
  } finally {
    setupBusy = false;
  }
}

// ── launch: stay on the homepage, narrate boot progress, poll lobby.json ───
// Auto-join on uses the backend-owned Hypixel routes; off uses open-only Lunar
// or Prism without --server. The dashboard opens only after an acknowledged
// live snapshot via handleLobbyPoll — not from cosmetic progress alone.
const PROGRESS_POLL_MS = 600;
let progressTimer = null;
let lobbyTimer = null;
let lobbyPollInFlight = false;
let lastKey = null;
let lastView = null;
let firstLiveSeen = false;
let lastLaunchKind = "lunar";
/** Route-specific manual subtitle preserved across unavailable polls. */
let manualRouteSubtitle = null;
/**
 * Hold the home view (button narration) until the first acknowledged live
 * lobby snapshot. Set for every confirmed game dispatch, both auto-join
 * states; see launch-view-route.js for the decision table.
 */
let homeUntilLive = false;

const connection = createConnectionModel();
const connectionShell = createConnectionShell({
  titleEl: el("joining-title"),
  subEl: el("joining-sub"),
  shellEl: joining,
  actionEl: el("conn-action"),
  announceEl: connAnnounce,
  actions: {
    preexisting_game: {
      label: "Try Again",
      onAction: () => {
        connection.clearForcedMode();
        connectionShell.hide();
        // Hiding the shell is not leaving the screen: the dashboard view is
        // still the one on top. A retry that is rejected or throws reports on
        // the home view, so the return to home happens HERE, before the retry -
        // a retry that succeeds routes itself away again through onLaunchReply.
        dash.classList.remove("on");
        stage.dataset.view = "home";
        refreshUi();
        startLaunch(lastLaunchKind, { retry: true });
      },
    },
    // The 60s escape hatch: the launch never reached Hypixel, so the same
    // forcing abort the home-view Cancel control uses gets the app back to
    // a launchable home instead of leaving it stranded here.
    waiting: {
      label: "Back to Home",
      onAction: () => sessionReset.resetToHome("launch_aborted"),
    },
  },
  clearRoster: () => {
    dash.classList.remove("on");
    dash.replaceChildren();
    delete dash.dataset.ctx;
    delete dash.dataset.density;
    delete dash.dataset.teams;
    lastKey = null;
    lastView = null;
  },
  stopProgress: () => {
    stopProgressPolling();
    suppressRefresh = false;
  },
  showDash: () => {
    stage.dataset.view = "dash";
  },
  hideDash: () => {
    dash.classList.remove("on");
  },
});

const launchController = createLaunchController(invoke, {
  onChange: () => refreshUi(),
});
const updateController = createUpdateController(invoke, { onChange: () => refreshUi() });

function applyPreviewUpdate(key) {
  previewUpdateKey = PREVIEW_UPDATE[key] ? key : "current";
  updateController.acceptStatus({ ...resolvePreviewUpdate(previewUpdateKey), channelLoaded: true });
}

function stopProgressPolling() {
  if (progressTimer) {
    clearInterval(progressTimer);
    progressTimer = null;
  }
}

function stopLobbyPolling() {
  if (lobbyTimer) {
    clearInterval(lobbyTimer);
    lobbyTimer = null;
  }
}

// Overlay-off quit / late-success rehide coordination (dependency-injected
// module; see overlay-exit.js for the exhaustive route table).
const overlayExit = createOverlayExit({
  rehide: (useExternalOverlay) => rehideAfterConfirmation(invoke, useExternalOverlay),
  quit: () => quitApp(invoke),
  // Double quit rejection: enter the overlay flow instead of stranding an
  // Active app - lobby polling gives it a live loop that can reach the
  // dashboard and, on game quit, the reset-to-home path.
  fallbackToOverlayFlow: () => startLobbyPolling(),
});

/** Shell copy for each reset reason, transitional and final. */
const RESET_SHELL_MODES = {
  game_session_changed: ["session_changed_resetting", "session_changed"],
  launch_aborted: ["launch_aborted_resetting", "launch_aborted"],
};
const resetShellMode = (reason, phase) => {
  const [transitional, final] =
    RESET_SHELL_MODES[reason] ?? ["session_ended_resetting", "session_ended"];
  return phase === "transitional" ? transitional : final;
};

// Session-ended-to-home orchestrator: invalidate first, reset, commit home
// atomically; truthful transitional copy + bounded retry on failure.
const sessionReset = createSessionReset({
  // A user-cancelled launch has no terminal to reset from - it takes the
  // forcing abort command; every backend-latched reason takes the ordinary
  // reset. Both answer in the same reply shape.
  invokeReset: (reason) =>
    reason === "launch_aborted" ? abortLaunchSession(invoke) : resetSessionEnd(invoke),
  stopPolling: () => {
    stopProgressPolling();
    stopLobbyPolling();
  },
  invalidate: () => {
    launchController.invalidateSession();
    // The reset owns the surface from here: the Cancel control goes away
    // before its own command has even answered.
    refreshUi();
  },
  commitHome: (prefs) => {
    overlayExit.resetSession();
    connection.reset();
    connectionShell.reset();
    connAnnounce.textContent = "";
    dash.classList.remove("on");
    dash.replaceChildren();
    delete dash.dataset.ctx;
    delete dash.dataset.density;
    delete dash.dataset.teams;
    lastKey = null;
    lastView = null;
    firstLiveSeen = false;
    manualRouteSubtitle = null;
    homeUntilLive = false;
    suppressRefresh = false;
    progressStage = null;
    launchController.resetLaunchSession(prefs);
    stage.dataset.view = "home";
    refreshUi();
    updateController.setGameActive(false);
  },
  refreshHome: async () => {
    const next = await invoke("refresh_setup");
    if (next) setStatus(next);
  },
  onRefreshError: (e) => {
    // Home stays visible with the setup-error key - recoverable, never silent.
    setStatus(setupErrorStatus(e));
  },
  showTransitional: (reason) => {
    connectionShell.enterTerminal(resetShellMode(reason, "transitional"));
    // Only a session that actually reached the dashboard shows this copy
    // there. A pre-live quit or a cancelled launch never left home
    // (`homeUntilLive` is still set): it stays on home, the key re-enabling
    // as the reset completes, with no dashboard flash on the way out.
    if (!homeUntilLive) stage.dataset.view = "dash";
  },
  showFinal: (reason) => {
    // The final copy is the recovery instruction ("quit and reopen"); it has
    // to be readable wherever the session was, so it always takes the shell.
    connectionShell.enterTerminal(resetShellMode(reason, "final"));
    stage.dataset.view = "dash";
  },
});

let progressPollInFlight = false;
let progressLaunchGen = 0;

function startProgressPolling() {
  stopProgressPolling();
  progressLaunchGen = launchController.getState().launchGen;
  progressTimer = setInterval(async () => {
    if (progressPollInFlight) return;
    if (launchController.getState().launchGen !== progressLaunchGen) {
      stopProgressPolling();
      return;
    }
    progressPollInFlight = true;
    try {
      const p = await launchController.pollProgress();
      if (launchController.getState().launchGen !== progressLaunchGen) return;
      if (p?.stage) {
        if (p.stage !== progressStage) {
          progressStage = p.stage;
          refreshUi();
        }
        if (p.stage === "settled" || p.stage === "forge_settled") {
          // Both auto-join states settle on the home key now.
          launchController.settleLaunchPhase();
          // Overlay-off quit / unconfirmed late-success trigger; the module
          // fires each obligation exactly once across duplicate polls.
          overlayExit.onConfirmationSignal("settled");
        }
      }
    } catch {
      // cosmetic fail-soft
    } finally {
      progressPollInFlight = false;
    }
  }, PROGRESS_POLL_MS);
}

const launchHooks = {
  onLaunchReply(reply) {
    if (reply.status === "preexisting_game") {
      connection.setForcedMode("preexisting_game");
      connectionShell.enterTerminal("preexisting_game", { showTryAgain: true });
      stage.dataset.view = "dash";
      return;
    }
    if (reply.status !== "launched") return;
    updateController.setGameActive(true);
    const autoJoin = reply.outcome.autoJoinHypixel;
    const now = Date.now();
    connection.setAutoJoin(autoJoin);
    connection.beginLaunchSession({ autoJoin, now });
    firstLiveSeen = false;
    launchController.bumpPollGen();
    const exitRoute = overlayExit.routeFor(reply.outcome);
    if (exitRoute === "quit_now") {
      // Never-fired open-only with overlay off: the backend proved the
      // launcher presented and quiescent - exit immediately after the
      // reply. (Unproven cleanup arrives as launch_unconfirmed instead.)
      overlayExit.quitNow();
      return;
    }
    const view = launchViewRoute(reply.outcome);
    homeUntilLive = view.stayHome;
    // A null subtitle tells the poll handler no manual screen exists.
    manualRouteSubtitle = view.subtitleKind
      ? manualRouteSubtitleFor(view.subtitleKind, lastLaunchKind)
      : null;
    // Every surviving route polls the lobby. The overlay-off confirmed route
    // polls HEADLESSLY (the marker suppresses its shell) purely so a
    // session_ended poll can still reset the app to a launchable home; its
    // quit-at-settled obligation is untouched.
    startLobbyPolling();
    if (autoJoin && reply.outcome.useExternalOverlay) {
      // The 60s escape hatch, armed only where a dashboard may appear at
      // all. The overlay-off route must stay headless: surfacing the
      // waiting dash there would also stop progress polling and starve
      // its quit-at-settled obligation on a slow cold start.
      connectionShell.scheduleWaitingDeadline(60_000, () => {
        stopProgressPolling();
        const { mode } = connection.stateAt(Date.now());
        if (mode === "waiting") {
          connectionShell.show("waiting");
          stage.dataset.view = "dash";
        }
      });
    }
    if (!view.stayHome) {
      // Open-only / unconfirmed: the manual screen IS the guidance.
      connectionShell.show(view.shellMode, { subtitle: manualRouteSubtitle });
      stage.dataset.view = "dash";
    }
    refreshUi();
  },
  onProgressStart() {
    startProgressPolling();
  },
};

/**
 * A launch that never produced a session - rejected by the backend, or the
 * bridge threw - leaves the app on an ordinary home, which is exactly where
 * the focus/background refresh belongs. Only a live session keeps the refresh
 * suppressed; its own reset-to-home clears the flag.
 */
function releaseRefreshUnlessLive() {
  if (launchController.getState().activeOutcome == null) suppressRefresh = false;
}

function startLaunch(kind, { retry = false } = {}) {
  lastLaunchKind = kind;
  suppressRefresh = true;
  progressStage = null;
  const run = retry ? launchController.tryAgainLaunch(kind, launchHooks) : launchController.launch(kind, launchHooks);
  return run.finally(releaseRefreshUnlessLive);
}

function enterDashboard() {
  connectionShell.enterConnected({ firstLive: !firstLiveSeen });
  firstLiveSeen = true;
  // The live snapshot the home view was held for: the marker's job is done.
  homeUntilLive = false;
  // First acknowledged live snapshot: the second late-success trigger
  // (covers dashboard entry stopping progress polling before settled).
  overlayExit.onConfirmationSignal("live");
}

function startLobbyPolling() {
  if (lobbyTimer) return;
  pollLobby();
  lobbyTimer = setInterval(pollLobby, LOBBY_POLL_MS);
}

async function pollLobby() {
  if (lobbyPollInFlight) return;
  lobbyPollInFlight = true;
  await launchController.pollLobbyOnce({ onPoll: handleLobbyPoll });
  lobbyPollInFlight = false;
}

async function handleLobbyPoll(poll) {
  await runLobbyPoll(poll, {
    connection,
    connectionShell,
    launchController,
    manualRouteSubtitle,
    homeUntilLive,
    firstLiveSeen,
    applyLobbySnapshot,
    resetToHome: (reason) => sessionReset.resetToHome(reason),
    stopLobbyPolling,
    setStageDash: () => {
      stage.dataset.view = "dash";
    },
  });
}

function applyLobbySnapshot(d, { reconnect = false } = {}) {
  const step = nextLiveDashboard(d, {
    firstLiveSeen,
    wasDisconnected: reconnect,
    eligibleViewOf: viewOfEligible,
  });
  if (step.enterDashboard) {
    enterDashboard();
  } else if (reconnect) {
    connectionShell.enterConnected({ reconnect: true });
  }
  const ctx = d.context;
  const key = `${ctx}:${d.seq ?? ""}:${step.eligible ? "1" : "0"}`;
  if (key !== lastKey) {
    try {
      const view = step.view;
      const held = dash.dataset.ctx === ctx.toLowerCase() ? currentCols() : 1;
      dash.innerHTML = sheetHtml(view, planCols(view, dash.clientWidth, held));
      lastView = view;
      lastKey = key;
      dash.dataset.ctx = ctx.toLowerCase();
      if (d.teams && d.teams.length) dash.dataset.teams = String(d.teams.length);
      else delete dash.dataset.teams;
      fitDash();
    } catch (e) {
      // The dashboard view is already up (it has to be: the dash is
      // display:none until then, so the column plan cannot measure it). A
      // render that throws used to return from here, leaving that view on
      // screen with an empty, never-shown roster - a black window with no
      // way back. Say so instead, and keep the next snapshot free to redraw.
      console.error(e);
      const note = document.createElement("div");
      note.className = "dash-error";
      note.textContent = `The roster could not be drawn. ${String(e)}`;
      dash.replaceChildren(note);
      delete dash.dataset.ctx;
      delete dash.dataset.teams;
      lastView = null;
      lastKey = null;
    }
  }
  dash.classList.add("on");
}

const currentCols = () =>
  Number(dash.firstElementChild?.style.getPropertyValue("--n")) || 1;

// A resized window is a different layout problem: a column may now fit that
// didn't, and the density that fitted the old height may not fit the new one.
// Re-columning means re-rendering, so it only happens when the count changes.
function syncDashLayout() {
  if (stage.dataset.view !== "dash" || !lastView || !dash.firstElementChild) return;
  const have = currentCols();
  const want = planCols(lastView, dash.clientWidth, have);
  if (want !== have) dash.innerHTML = sheetHtml(lastView, want);
  fitDash();
}
new ResizeObserver(syncDashLayout).observe(stage);

// Compact cozy → compact → dense so the roster tries to fit the window.
// If it still overflows after dense, the dash scrolls.
function fitDash() {
  dash.dataset.density = "cozy";
  for (const step of ["compact", "dense"]) {
    if (dash.scrollHeight <= dash.clientHeight) return;
    dash.dataset.density = step;
  }
}

// ── dashboard rendering (from ~/.cobblify/lobby.json) ───────────────────────
// Every decision on a row was made in the mod (see row-model.js); this only
// draws the model it is handed.

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

// OK players high→low by FKDR; unresolved states (loading/nick/never/error)
// keep their given order below them; players who are out of the game last.
const bySweat = sortRoster;

function pname(p, chips) {
  const flags = chips && chips.length ? `<span class="flags-inline">${chips.join("")}</span>` : "";
  return `<div class="pname"><span class="sweat-bar"></span>
    <div class="identity">${rankHtml(p)}<span class="pn">${esc(p.name)}</span></div>${flags}</div>`;
}

// The mod exports the rank with its own § colour codes; each code becomes a
// class. A row with no codes falls back to the plain label.
function rankHtml(p) {
  const spans = sectionSpans(p.rankCodes);
  if (spans.length) {
    const inner = spans
      .map((s) => (s.cls ? `<span class="${s.cls}">${esc(s.text)}</span>` : esc(s.text)))
      .join("");
    return `<span class="rank">${inner}</span>`;
  }
  const raw = String(p.rank ?? "").trim();
  return raw ? `<span class="rank">${esc(raw)}</span>` : "";
}

const TEAM_SLUG = {
  Red: "red",
  Blue: "blue",
  Green: "green",
  Yellow: "yellow",
  Aqua: "aqua",
  White: "white",
  Pink: "pink",
  Gray: "gray",
};

function teamClass(name) {
  const slug = TEAM_SLUG[name];
  return slug ? ` team-${slug}` : "";
}

// A chip carries the mod's colour as a class (see .dash .chip.mc-*); the
// style decides how much of that colour the sheet shows.
const chipHtml = (c) => {
  const mc = colorClass(c.color);
  return `<span class="chip ${c.cls}${mc ? ` ${mc}` : ""}">${esc(c.text)}</span>`;
};
const cellHtml = (c) =>
  c.skeleton
    ? `<div class="stat ${c.cls}"><span class="skel" style="width:${c.width}px"></span></div>`
    : `<div class="stat${c.muted ? " muted" : ""} ${c.cls}">${esc(c.text)}</div>`;

// A player who is out of the game keeps their row - dimmed, badged, and
// struck through once eliminated - so the team never shrinks or reorders
// under the reader. The model decides the classes, chips and cells.
function row(p, teamName) {
  const m = rowModel(p);
  const cls = [...m.classes, teamClass(teamName).trim()].filter(Boolean).join(" ");
  return `<div class="${cls}">
    ${pname(p, m.chips.map(chipHtml))}
    ${m.cells.map(cellHtml).join("")}</div>`;
}

// ── column layout ───────────────────────────────────────────────────────────
// A view is { title, sub, blocks }; a block is a section - a head plus its rows
// - or an atomic one (a team) that has to stay whole. A window wide enough for
// two readable columns gets two, so the roster fills the width instead of
// sitting in a strip with black either side. The split is done here rather than
// in CSS because every column has to be headed and a team must not be cut in
// half. One column produces exactly the markup this always had.
const MIN_COL = 560; // narrowest column that still fits a name and four stats
const COL_GAP = 26; // keep in step with --gap in the stylesheet
const PER_COL = 9; // lines wanted before a second column is worth opening
const MAX_COLS = 3;

const blockRows = (b) => b.n ?? b.rows?.length ?? 0;

// Columns are worth opening only if there is width for a readable one and
// enough lines to fill it. `current` is what is on screen now: a column, once
// opened, is held until the roster drops well under the point that opened it,
// so players trickling in and out of a lobby can't flap the layout.
function planCols(view, width, current) {
  const lines = units(view.blocks).reduce((s, u) => s + u.w, 0);
  const byWidth = Math.floor((width + COL_GAP) / (MIN_COL + COL_GAP));
  const open = Math.ceil(lines / PER_COL);
  const hold = current > 1 && lines >= (current - 1) * PER_COL - 3 ? current : 1;
  return Math.max(1, Math.min(MAX_COLS, byWidth, Math.max(open, hold)));
}

// Flatten to placeable units. A head weighs what a row weighs; an atomic block
// weighs what it would have weighed split.
function units(blocks) {
  const out = [];
  for (const b of blocks) {
    if (b.atomic) {
      out.push({ html: b.atomic, w: blockRows(b) + 1 });
      continue;
    }
    if (b.head) out.push({ html: b.head, w: 1, head: true });
    for (const r of b.rows ?? []) out.push({ html: r, w: 1 });
    if (b.foot) out.push({ html: b.foot, w: 1 });
  }
  return out;
}

// Greedy fill to an even share. A column never ends on a section head - a head
// belongs with the rows it labels - and never opens with nothing left to place.
function columnize(blocks, n) {
  const list = units(blocks);
  if (n <= 1) return [list];
  const share = list.reduce((s, u) => s + u.w, 0) / n;
  const cols = [];
  let col = [];
  let acc = 0;
  list.forEach((u, i) => {
    col.push(u);
    acc += u.w;
    const left = n - cols.length - 1; // columns still to open after this one
    const rest = list.length - i - 1; // units still to place
    if (left > 0 && rest > left && acc >= share && !u.head) {
      cols.push(col);
      col = [];
      acc = 0;
    }
  });
  cols.push(col);
  return cols;
}

const STAT_LABELS = `<span>FKDR</span><span>WLR</span><span>Finals</span><span>K/D</span>`;

function sheetHtml(view, n) {
  const cols = columnize(view.blocks, n);
  // What columnize could actually fill, which is fewer than asked for when the
  // blocks refuse to divide - one team bigger than the share, say. The sheet is
  // told that number, never the wish, so it can't lay out an empty track.
  const k = cols.length;
  const body = cols
    .map((col) => {
      const labels = !view.bare && k > 1 ? `<div class="col-head"><span></span>${STAT_LABELS}</div>` : "";
      return `<div class="dcol">${labels}${col.map((u) => u.html).join("")}</div>`;
    })
    .join("");
  if (view.bare) {
    return `<div class="dsheet bare" style="--n:${k}">${body}</div>`;
  }
  const ctx = `<span class="ctx"><span class="live"></span>
      <span class="ctx-title">${view.title}</span>
      <span class="ctx-sub">${view.sub}</span></span>`;
  // One column keeps the context and the labels on a single sticky row. Past
  // that the labels can no longer line up with one shared header, so each
  // column heads itself and the bar carries the context alone.
  const head =
    k > 1
      ? `<div class="dash-sticky ctx-bar">${ctx}</div>`
      : `<div class="dash-sticky col-head">${ctx}${STAT_LABELS}</div>`;
  return `<div class="dsheet" style="--n:${k}">${head}${body}</div>`;
}

function sectHead(title, note) {
  return `<div class="sect"><h3>${title}</h3><span class="n">${note}</span></div>`;
}

function roster(players, teamOf) {
  if (!players || !players.length) return "";
  return `<div class="roster">${players.map((p) => row(p, teamNameFor(teamOf, p))).join("")}</div>`;
}

function teamOfPlayers(d) {
  const map = new Map();
  for (const t of d.teams ?? []) {
    for (const p of t.players ?? []) {
      for (const name of aliases(p)) map.set(name, t.name);
    }
  }
  return map;
}

function teamNameFor(teamOf, player) {
  if (!teamOf) return undefined;
  for (const name of aliases(player)) {
    const team = teamOf.get(name);
    if (team) return team;
  }
  return undefined;
}

// The party keeps its own order (you, then your party, then the rest of your
// team) - it is not ranked by sweat - but a member who is out of the game
// drops below the ones still in it, like every other block.
function partyBlock(list, teamOf) {
  if (!list || !list.length) return null;
  return {
    head: sectHead("Your Party", list.length),
    rows: activeFirst(list).map((p) => row(p, teamNameFor(teamOf, p))),
  };
}

function viewOf(d) {
  return nextLiveDashboard(d, { firstLiveSeen: true, eligibleViewOf: viewOfEligible }).view;
}

function viewOfEligible(d) {
  const ctx = d.context;
  if (ctx === "QUEUE") return viewQueue(d);
  if (ctx === "GAME") return viewGame(d);
  return viewLobby(d);
}

function viewLobby(d) {
  const all = d.players ?? [];
  const partyPlayers = dashboardParty(d);
  const rest = bySweat(withoutPlayers(all, partyPlayers));
  const blocks = [];
  if (rest.length) {
    blocks.push({ head: sectHead("Lobby", rest.length), rows: rest.map((p) => row(p)) });
  }
  const party = partyBlock(partyPlayers);
  if (party) blocks.push(party);
  return { title: "Main Lobby", sub: `· ${all.length} players`, blocks };
}

function viewQueue(d) {
  const all = d.players ?? [];
  const partyPlayers = dashboardParty(d);
  const rest = bySweat(withoutPlayers(all, partyPlayers));
  const parties = typeof d.partyCount === "number" ? `${d.partyCount} parties · ` : "";
  const hidden = `<div class="empty-note">Remaining players stay hidden by Hypixel until the match starts.</div>`;
  const blocks = [
    rest.length
      ? {
          head: sectHead("Chatted", "only typed players resolve"),
          rows: rest.map((p) => row(p)),
          foot: hidden,
        }
      : { rows: [], foot: hidden },
  ];
  const party = partyBlock(partyPlayers);
  if (party) blocks.push(party);
  return {
    title: d.mode ? `${esc(d.mode)} Queue` : "Queue",
    sub: `· ${parties}${all.length} known`,
    blocks,
  };
}

function viewGame(d) {
  const teamOf = teamOfPlayers(d);
  const partyPlayers = dashboardParty(d);
  // Order, averages and the Target choice are decided in roster-identity.js
  // (pure, tested); this only draws what it is handed.
  const blocks = rankedOpponentTeams(d).map(({ team, players, agg, standing, target }) => {
    const slug = TEAM_SLUG[team.name];
    const teamCls = (slug ? ` team-${slug}` : "") + (standing === "eliminated" ? " is-eliminated" : "");
    const rail =
      standing === "eliminated"
        ? '<span class="agg">Eliminated</span>'
        : `<span class="agg">avg FKDR ${agg === null ? "—" : agg.toFixed(1)}</span>
        ${target ? '<span class="target-badge">Target</span>' : ""}`;
    // Atomic: a team's border, colour scope and rows are placed as one unit. The
    // team is named by its border colour alone; the rail breaks that border to
    // carry the average, and the target flag on the sweatiest team.
    return {
      n: players.length,
      atomic: `<div class="team${teamCls}"><div class="team-rail">${rail}</div>
        ${roster(players, teamOf)}</div>`,
    };
  });
  const party = partyBlock(partyPlayers, teamOf);
  if (party) blocks.push(party);
  return {
    title: d.mode ? esc(d.mode) : "Live Match",
    sub: `· ${(d.teams ?? []).length} teams`,
    blocks,
  };
}

// ── boot ────────────────────────────────────────────────────────────────────
const previewing = !window.__TAURI__;
if (previewing) document.body.classList.add("previewing");

const bootParams = new URLSearchParams(location.search);

function afterStatus(status) {
  setStatus(status);
  if (!previewing) launchController.refreshPreferences();
  if (!previewing) return;
  const ctx = bootParams.get("ctx");
  if (ctx === "joining") {
    stage.dataset.view = "dash";
    connectionShell.show("joining");
  } else if (ctx === "waiting") {
    stage.dataset.view = "dash";
    connectionShell.show("waiting");
  } else if (ctx === "disconnected") {
    stage.dataset.view = "dash";
    connectionShell.enterDisconnected();
  } else if (ctx === "preexisting_game") {
    stage.dataset.view = "dash";
    connectionShell.enterTerminal("preexisting_game", { showTryAgain: true });
  } else if (
    ctx === "session_ended" ||
    ctx === "session_changed" ||
    ctx === "launch_aborted" ||
    ctx === "session_ended_resetting"
  ) {
    stage.dataset.view = "dash";
    connectionShell.enterTerminal(ctx);
  } else if (PREVIEW_LOBBY[ctx]) {
    stage.dataset.view = "dash";
    applyLobbySnapshot(PREVIEW_LOBBY[ctx]);
  }
}

/** One setup-error projection for boot and reset-home refresh failures. */
function setupErrorStatus(e) {
  return {
    state: "error",
    message: String(e),
    mod_version: null,
    targets: [],
  };
}

refreshUi();
openInfo(invoke)
  .then((info) => {
    openInfoReply = info;
    refreshUi();
  })
  .catch(() => {});

if (!(previewing && bootParams.get("state") === "loading")) {
  invoke("refresh_setup")
    .then(afterStatus)
    .catch((e) => afterStatus(setupErrorStatus(e)));
}

if (previewing) {
  applyPreviewUpdate(bootParams.get("update") || "current");
} else {
  updateController.bootstrap().catch(() => {
    updateController.acceptStatus({ state: "error", manual: false });
  });
}

if (!previewing && window.__TAURI__?.event?.listen) {
  window.__TAURI__.event.listen("updater://status", ({ payload }) => {
    updateController.acceptStatus(payload);
  });
}

function canAutoRefresh() {
  if (previewing || suppressRefresh || setupBusy) return false;
  if (stage.dataset.view === "dash") return false;
  const st = launchController.getState();
  if (st.launchBusy || st.launchPhase === "loading") return false;
  return true;
}

function scheduleRefresh() {
  if (!canAutoRefresh()) return;
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(async () => {
    if (!canAutoRefresh()) return;
    try {
      const next = await invoke("refresh_setup");
      if (next) setStatus(next);
    } catch {
      // fail-soft on background refresh
    }
  }, 400);
}

if (!previewing) {
  let lostFocus = false;
  window.addEventListener("blur", () => {
    lostFocus = true;
  });
  window.addEventListener("focus", () => {
    if (!lostFocus) return;
    lostFocus = false;
    scheduleRefresh();
  });
}
