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
  SETUP_EVENT,
  abortLaunchSession,
  beginSetup,
  gameTheme,
  openInfo,
  quitApp,
  rehideAfterConfirmation,
  resetSessionEnd,
  resumeSetup,
  setupState,
} from "./tauri-contract.js";
import { LOBBY_POLL_MS } from "./poll-config.js";
import { manualRouteSubtitleFor } from "./connection-view.js";
import { launchViewRoute, showsCancelLaunch } from "./launch-view-route.js";
import { createApp } from "./ui/app.js";
import { CL, uiId } from "./ui/art.js";
import { NEW_PLAYER_THEME, themeFor } from "./ui/accent.js";
import { setAccent, setFont, watchUnits, wordmark } from "./ui/kit.js";
import { homeModel, isInstalled, isSetUp, prismInstances, sheetModel, stuckAfter } from "./ui/model.js";

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
 *   ?ctx=lobby|unsupported|disconnected  lobby dashboard / disconnected
 *   ?outcome=wrong_version|not_loaded  how a preview launch ends (launch_progress' outcome)
 *   ?first=1  a Prism preview launch is a made instance's first (Downloading Minecraft)
 *   ?ctx=preexisting_game|session_ended|session_changed|launch_aborted
 *   ?ctx=queueSolo|queueDoubles|queueThrees|queueFours
 *   ?ctx=gameSolo|gameDoubles|gameThrees|gameFours|game4v4
 *   ?open=updated|fresh&version=…  what open_info answers
 *   ?accent=<token>&font=minecraft|modern  what game_theme answers
 *   ?setup=signin|game|fail  a preview setup stops at sign-in / Close Minecraft, or fails once at check 5
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
let previewLaunchForge = false;
let previewUpdateKey = "consent";
/** Preview: clients whose download page was opened (installed 2.4 s later), and clients set up meanwhile. */
const previewInstalled = new Set();
const previewDone = new Set();
let previewSetupGen = 0;
let previewFailed = null;

/** Preview: a fixture with the 0.18 facts (installed, set_up, instances) and what happened since. */
function previewStatus(which) {
  const s = resolvePreviewStatus(which);
  return {
    ...s,
    targets: s.targets.map((t) => {
      const done = previewDone.has(t.kind);
      const out = { ...t, installed: t.issue !== "missing_launcher" || previewInstalled.has(t.kind), set_up: t.state === "ready" || done };
      if (done && t.state !== "ready") Object.assign(out, { state: "ready", issue: null, action: "none", candidates: [] });
      if (t.kind === "forge") {
        out.instances = (t.candidates ?? []).map((c, i) => ({ id: c.id, name: c.name, detail: "Forge 11.15.1.2318", last_launch_ms: 100 - i }));
      }
      return out;
    }),
  };
}

/** Preview: a setup run at the prototype's pacing, with the stop or failure `?setup=` asks for. */
function previewSetupRun(kind, from) {
  const gen = ++previewSetupGen;
  const stopAt = new URLSearchParams(location.search).get("setup");
  let k = from;
  const snap = (check, extra = {}) => ({ gen, kind, check, phase: "running", ...extra });
  const step = () => {
    if (gen !== previewSetupGen) return;
    if (k > 6) {
      previewDone.add(kind);
      onSetupSnapshot(snap(6, { phase: "done" }));
      return;
    }
    if (k === 3 && stopAt === "signin" && !previewDone.has("signin:" + kind)) {
      previewDone.add("signin:" + kind);
      onSetupSnapshot(snap(2, { phase: "stop", stop: "signin" }));
      setTimeout(step, 3000);
      return;
    }
    if (k === 4 && stopAt === "game" && !previewDone.has("game:" + kind)) {
      previewDone.add("game:" + kind);
      onSetupSnapshot(snap(3, { phase: "stop", stop: "game_open" }));
      setTimeout(step, 3000);
      return;
    }
    if (k === 5 && stopAt === "fail" && !previewDone.has("fail:" + kind)) {
      previewDone.add("fail:" + kind);
      previewFailed = { kind, at: 5 };
      onSetupSnapshot(snap(4, { phase: "failed", failedAt: 5 }));
      return;
    }
    onSetupSnapshot(snap(k - 1));
    k++;
    setTimeout(step, 900);
  };
  setTimeout(step, 0);
  return snap(from - 1);
}

function previewInvoke(command, args) {
  if (command === "status" || command === "refresh_setup") {
    const which = new URLSearchParams(location.search).get("state");
    return new Promise((resolve) =>
      setTimeout(() => resolve(previewStatus(which)), 250),
    );
  }

  if (command === "begin_setup") {
    return Promise.resolve(previewSetupRun(args?.kind ?? "lunar", 1));
  }
  if (command === "resume_setup") {
    if (!previewFailed || previewFailed.kind !== args?.kind) return Promise.reject("nothing_to_resume");
    const at = previewFailed.at;
    previewFailed = null;
    return Promise.resolve(previewSetupRun(args.kind, at));
  }
  if (command === "setup_state") {
    return Promise.resolve(null);
  }

  if (command === "get_launcher") {
    const kind = args?.kind ?? "lunar";
    setTimeout(() => previewInstalled.add(kind), 2400);
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
      useExternalOverlay: true,
      health: "missing",
    });
  }

  if (command === "set_use_external_overlay") {
    return Promise.resolve({
      status: "saved",
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
      preferences: { useExternalOverlay: true, health: "valid" },
    });
  }

  if (command === "rehide_after_confirmation" || command === "quit_app") {
    // Preview never exits the page; quit is a no-op here.
    return Promise.resolve();
  }

  if (command === "launch_lunar" || command === "launch_forge") {
    previewLaunchAt = Date.now();
    previewLaunchForge = command === "launch_forge";
    return Promise.resolve({
      status: "launched",
      outcome: {
        useExternalOverlay: args?.expectedUseExternalOverlay ?? true,
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
    const q = new URLSearchParams(location.search);
    const t = previewLaunchAt ? Date.now() - previewLaunchAt : 0;
    const prefix = previewLaunchForge ? "forge_" : "";
    const outcome = q.get("outcome");
    if (outcome && t >= 2300) return Promise.resolve({ stage: `${prefix}discovered`, percent: 60, outcome });
    // A made instance's first launch: Prism downloads Minecraft first (prototype pacing).
    const download = previewLaunchForge && q.get("first") ? 3200 : 0;
    if (download && t >= 900 && t < 900 + download) {
      return Promise.resolve({ stage: "forge_downloading", percent: 22 });
    }
    const steps = [
      [3000 + download, "settled", 100],
      [2300 + download, "mixing", 85],
      [1600 + download, "discovered", 60],
      [900 + download, "attached", 35],
    ];
    for (const [ms, s, percent] of steps) {
      if (t >= ms) return Promise.resolve({ stage: `${prefix}${s}`, percent });
    }
    return Promise.resolve({ stage: `${prefix}fired`, percent: 10 });
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
let refreshTimer = null;
let suppressRefresh = false;
/** The remembered client (decision 5). */
let selected = readStoredClient();
/** The latest backend launch stage (progress.rs), shown as the Launch key's phase. */
let progressStage = null;
/**
 * How the last launch ended when Cobblify did not come up in it: the progress poll's
 * `outcome` ("wrong_version" | "not_loaded") and the client it was for. Kept on the home the
 * launch returns to, until the next launch or another client is picked.
 * @type {{ outcome: string, kind: string } | null}
 */
let launchOutcome = null;
/** The dev switch's value while its save is in flight. */
let devPending = null;
/** open_info: the running version, and whether this is the first open after an update. */
let openInfoReply = null;
let openPlayed = false;
let themeKind = null;
/** The last home model drawn (the add-wait follows through only on an idle home). */
let lastHome = null;

// ── setting a client up (spec-flows.md §1.1-1.7) ────────────────────────────
/** The setup run the window shows (a `setup://progress` snapshot), or null. Failed stays until Try again. */
let setupSnap = null;
/** The check whose island part hangs grey (model.stuckAfter). */
let setupStuckAt = null;
/** Setup is done: the bar holds full while the last part and the bed land. */
let setupHolding = false;
/** The last run that finished: its snapshots (a late begin reply included) are never shown again. */
let doneGen = -1;
/** A begin/resume request is in flight (one setup at a time). */
let setupRequest = false;
/** First run: the client whose download page was opened; setup starts by itself once it is installed. */
let installWait = null;
/** Home: "Add <client>" opened its download page; its setup starts once it is installed and home is idle. Session only. */
let addWait = null;
/** The Prism instance pick: { picked } while it is up. */
let pick = null;
/** Setup just finished: the ready line (one client) until the next launch or pick. */
let freshSetup = false;

const INSTALL_POLL_MS = 2000;
let installTimer = null;

const ui = createApp(el("app"), {
  play: (id) => startLaunch(CL[id]?.kind ?? "lunar"),
  cancel: () => sessionReset.resetToHome("launch_aborted"),
  select: (id) => {
    if (launchOutcome && launchOutcome.kind !== CL[id]?.kind) launchOutcome = null;
    selected = id;
    freshSetup = false;
    storeClient(id);
    refreshUi();
  },
  // Choose Your Setup's installed row; settings Repair; the add row of an installed client.
  setupNow: (id) => addClient(id),
  // Choose Your Setup's row for a client that isn't installed: its download page, then wait for the install.
  get: (id) => awaitInstall(id),
  add: (desc) => {
    if (!desc) return;
    if (desc.installed) {
      addClient(desc.id);
      return;
    }
    // Settings' Add before anything is set up: the first run's own wait.
    if (lastHome?.hero?.kind === "setup") {
      awaitInstall(desc.id);
      return;
    }
    addWait = desc.id;
    openDownload(desc.id);
    refreshUi();
    watchInstall();
  },
  download: (id) => openDownload(id),
  openClient: (id) => {
    invoke("open_launcher", { kind: CL[id].kind }).catch((e) => console.warn("open_launcher failed", e));
  },
  retrySetup: () => retrySetup(),
  repairSetup: (id) => addClient(id),
  setupPrism: () => {
    const id = pick?.picked ?? prismInstances(lastStatus)[0]?.id;
    addClient("prism", id ?? undefined);
  },
  pick: (id) => {
    if (!pick) return;
    pick = { picked: id };
    refreshUi();
  },
  // Settings' Change: the instance pick, then setup on the chosen instance.
  change: () => openPick(),
  refresh: () => {
    invoke("refresh_setup")
      .then((next) => next && setStatus(next))
      .catch((e) => setStatus(setupErrorStatus(e)));
  },
  repairPref: () => {
    // Any save rewrites a valid document (the backend salvages every valid key, and writes
    // back the key the 0.17 launcher needs); an uncertain save repairs with its own value.
    const st = launchController.getState();
    launchController.setOverlay(st.optimisticOverlay, { repair: true });
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
  // Repair re-runs Lunar's setup through the flow (default 12).
  repairLunar: () => addClient("lunar"),
  // The sheet or What's New closed: the add-wait may follow through now.
  idle: () => addWaitGo(),
});

/** First run: the client's download page opens and Choose Your Setup waits for the install (spec 1.1). */
function awaitInstall(id) {
  installWait = id;
  openDownload(id);
  refreshUi();
  watchInstall();
}

/** Opens a client's download page in the browser (get_launcher). */
function openDownload(id) {
  invoke("get_launcher", { kind: CL[id].kind }).catch((e) => console.warn("get_launcher failed", e));
}

const setupShown = () => setupSnap != null;
/** A setup run is under way (begun and not failed): nothing else starts one. */
const setupRunning = () => setupRequest || (setupSnap != null && setupSnap.phase !== "failed");

/**
 * Sets a client up (proto addClient): the menu and the sheet close, a pending wait is dropped. Prism with several
 * compatible instances and none picked shows the pick first; a Prism that is already set up uses its own instance.
 */
async function addClient(id, instanceId) {
  if (setupRunning()) return;
  const st = launchController.getState();
  if (st.activeOutcome != null || st.launchBusy) return;
  ui.closeOverlays();
  installWait = null;
  if (addWait === id) addWait = null;
  pick = null;
  const kind = CL[id].kind;
  if (kind === "forge" && instanceId === undefined && !isSetUp(lastStatus, "prism") && prismInstances(lastStatus).length >= 2) {
    openPick();
    return;
  }
  setupRequest = true;
  try {
    const snap = await beginSetup(invoke, kind, instanceId);
    setupRequest = false;
    onSetupSnapshot(snap, { fromReply: true, fresh: true });
  } catch (e) {
    setupRequest = false;
    if (String(e) === "choose_instance") {
      await refreshStatusQuietly();
      openPick();
      return;
    }
    console.warn("begin_setup failed", kind, e);
    refreshUi();
  }
}

/** Try again: the failed setup resumes at the check it failed at (default 22). */
async function retrySetup() {
  if (!setupSnap || setupSnap.phase !== "failed" || setupRequest) return;
  const kind = setupSnap.kind;
  setupRequest = true;
  try {
    const snap = await resumeSetup(invoke, kind);
    setupRequest = false;
    onSetupSnapshot(snap, { fromReply: true });
  } catch (e) {
    setupRequest = false;
    if (String(e) === "nothing_to_resume") {
      // The backend no longer holds the failure (it began elsewhere, or restarted): start over.
      setupSnap = null;
      setupStuckAt = null;
      addClient(uiId(kind));
      return;
    }
    console.warn("resume_setup failed", kind, e);
  }
}

/** The Prism instance pick: the most recently played instance is preselected (the backend sorts them). */
function openPick() {
  if (setupRunning()) return;
  ui.closeOverlays();
  pick = { picked: prismInstances(lastStatus)[0]?.id ?? null };
  refreshUi();
}

/**
 * Every setup snapshot, from `setup://progress`, the begin/resume replies and `setup_state` at boot. Snapshots of an
 * older run are dropped, as is a reply its own run's events already overtook.
 */
function onSetupSnapshot(s, { fromReply = false, fresh = false } = {}) {
  if (!s || typeof s !== "object") return;
  if (setupHolding || s.gen <= doneGen) return;
  if (setupSnap && s.gen < setupSnap.gen) return;
  if (fromReply && setupSnap && s.gen === setupSnap.gen) return;
  // A finished run nobody watched (a reload after it ended) is not shown; one whose begin is still answering is.
  if (!setupSnap && !setupRequest && s.phase === "done") return;
  // A new run starting below the hanging part is a fresh begin, not the resume that keeps it.
  if (fresh || (setupSnap && s.gen !== setupSnap.gen && setupStuckAt != null && s.check < setupStuckAt)) setupStuckAt = null;
  setupStuckAt = stuckAfter(setupStuckAt, s);
  setupSnap = s;
  pick = null;
  freshSetup = false;
  if (s.phase === "done") {
    finishSetup(s);
    return;
  }
  refreshUi();
}

/**
 * The last check passed: the bar holds full while the last part and then the bed land, and Launch comes with the
 * bed (proto setupDone). Meanwhile the status is read again, so home has the new client.
 */
function finishSetup(s) {
  setupHolding = true;
  doneGen = s.gen;
  refreshUi();
  const land = new Promise((r) => setTimeout(r, ui.buildEnd()));
  Promise.all([refreshStatusQuietly(), land]).then(() => {
    const id = uiId(s.kind);
    setupHolding = false;
    setupSnap = null;
    setupStuckAt = null;
    selected = id;
    storeClient(id);
    freshSetup = true;
    launchOutcome = null;
    refreshUi();
    addWaitGo();
  });
}

/** refresh_setup, falling back to the cached `status` when a refresh is refused (busy). Never throws. */
async function refreshStatusQuietly() {
  try {
    const next = await invoke("refresh_setup");
    if (next) setStatus(next);
    return;
  } catch {
    // refused (another claim holds the lifecycle): the cached status is still current enough
  }
  try {
    const next = await invoke("status");
    if (next) setStatus(next);
  } catch {
    // keep what we have
  }
}

/** An idle home (proto idleHome): Launch showing, nothing running, no sheet, no What's New, no setup or pick. */
function homeIdle() {
  const s = ui.state;
  return (
    !!lastHome &&
    lastHome.hero.kind === "play" &&
    !lastHome.launching &&
    !lastHome.under?.kind?.match(/setLunar|notLoaded/) &&
    !setupShown() &&
    !setupRequest &&
    !pick &&
    stage.dataset.view !== "dash" &&
    !s.sheetOpen &&
    !s.modal &&
    s.view === "home"
  );
}

/** Add's wait: once that client is installed and home is idle, its setup starts. */
function addWaitGo() {
  if (!addWait || !isInstalled(lastStatus, addWait) || !homeIdle()) return false;
  const id = addWait;
  addWait = null;
  addClient(id);
  return true;
}

/** Polls detection every 2 s while an install is awaited (default 2). */
function watchInstall() {
  if (installTimer) return;
  installTimer = setInterval(pollInstall, INSTALL_POLL_MS);
}

let installPollInFlight = false;
async function pollInstall() {
  if (!installWait && !addWait) {
    clearInterval(installTimer);
    installTimer = null;
    return;
  }
  if (installPollInFlight || suppressRefresh || setupRunning() || stage.dataset.view === "dash") return;
  const st = launchController.getState();
  if (st.launchBusy || st.activeOutcome != null) return;
  installPollInFlight = true;
  try {
    const next = await invoke("refresh_setup");
    if (next) setStatus(next);
  } catch {
    // fail-soft: the next poll tries again
  } finally {
    installPollInFlight = false;
  }
  if (installWait && isInstalled(lastStatus, installWait)) addClient(installWait);
  else addWaitGo();
}

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
    outcome: launchOutcome?.outcome ?? null,
    outcomeKind: launchOutcome?.kind ?? null,
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
  const home = homeModel({
    status: lastStatus,
    selected,
    launch,
    update,
    setup: setupSnap ? { snap: setupSnap, stuckAt: setupStuckAt } : null,
    wait: installWait,
    addWait,
    pick,
    freshSetup,
  });
  lastHome = home;
  const sheet = sheetModel({
    status: lastStatus,
    launch,
    update,
    overlayOn: st.optimisticOverlay,
    overlaySaving: st.prefSaving,
    version,
    devPending,
    setupActive: setupShown() || setupRequest || !!pick,
  });
  ui.update({ home, sheet, version, dev: update.updateChannel === "dev" });
  applyTheme(home.sel);
  maybeOpen(home);
  maybeShowWindow();
}

// ── the window: created hidden, shown once its first frame is drawn ─────────
// tauri.conf.json creates the window with `visible: false` so the island never flashes in half-drawn. It is shown
// after the island's first frame (at once when there is no island to wait for), and 1.5 s after boot at the latest;
// the backend also shows it 3 s after start if it is still hidden, so a script failure can never leave it invisible.
const SHOW_WINDOW_MAX_MS = 1500;
let windowShown = false;
let windowAsked = false;
function showWindow() {
  if (windowShown) return;
  windowShown = true;
  try {
    const w = window.__TAURI__?.window?.getCurrentWindow?.();
    Promise.resolve(w?.show?.()).catch(() => {});
  } catch {
    // the backend's own timer shows it
  }
}
function maybeShowWindow() {
  if (windowShown || windowAsked || lastStatus.state === "loading") return;
  windowAsked = true;
  ui.firstFrame().then(showWindow, showWindow);
}
setTimeout(showWindow, SHOW_WINDOW_MAX_MS);

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

/**
 * The first open after an update: the island's build replay, then What's New. Once, on the first READY home of the
 * session: a first home that is blocked, setting up or picking does not use it up (code review round 1, I1); it is
 * marked played only when ui.open actually played it.
 */
function maybeOpen(home) {
  if (openPlayed || !openInfoReply || lastStatus.state === "loading" || home.launching) return;
  if (!openInfoReply.updated) {
    openPlayed = true;
    return;
  }
  openPlayed = ui.open(openInfoReply) === true;
}

/** The highest backend status revision applied (main.rs `Status.rev`); -1 before any. */
let lastStatusRev = -1;

/**
 * Applies a status reply. A reply carrying an older `rev` than the last one applied is a refresh that read the
 * clients before a newer publish (a setup finishing) and is ignored (code review round 1, I2). Replies without a
 * `rev` (an error made here) always apply.
 */
function setStatus(status) {
  const rev = typeof status?.rev === "number" ? status.rev : null;
  if (rev !== null) {
    if (rev < lastStatusRev) return;
    lastStatusRev = rev;
  }
  lastStatus = status;
  // A fresh setup read also re-reads the game's theme: the player may have changed it in game meanwhile.
  themeKind = null;
  refreshUi();
}

// ── launch: stay on the homepage, narrate boot progress, poll lobby.json ───
// Launches never join a server: Lunar opens through the bare play link, Prism
// without --server, and the player joins Hypixel themselves. The dashboard
// opens only after an acknowledged live snapshot via handleLobbyPoll - not
// from cosmetic progress alone. A progress `outcome` (wrong Lunar version,
// Cobblify didn't load) ends the launch back on home with that outcome.
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
 * lobby snapshot. Set for every confirmed game dispatch; see
 * launch-view-route.js for the decision table.
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
  // A launch that ended with an outcome; its view is home (only the final give-up uses this).
  launch_outcome: ["launch_aborted_resetting", "launch_aborted"],
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
    reason === "launch_aborted" || reason === "launch_outcome"
      ? abortLaunchSession(invoke)
      : resetSessionEnd(invoke),
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
    // Cancel, or the game ended: an add-wait follows through on the idle home.
    addWaitGo();
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
    if (reason === "launch_outcome") {
      // The launch ended with an outcome: home is where it is shown, never a shell.
      stage.dataset.view = "home";
      return;
    }
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
      if (p?.outcome) {
        endLaunchWithOutcome(p.outcome);
        return;
      }
      if (p?.stage) {
        if (p.stage !== progressStage) {
          progressStage = p.stage;
          refreshUi();
        }
        if (p.stage === "settled" || p.stage === "forge_settled") {
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
    connection.beginLaunchSession({ now: Date.now() });
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
  launchOutcome = null;
  freshSetup = false;
  const run = retry ? launchController.tryAgainLaunch(kind, launchHooks) : launchController.launch(kind, launchHooks);
  return run.finally(releaseRefreshUnlessLive);
}

/**
 * The launch ended without Cobblify: the backend's verdict (`wrong_version` / `not_loaded`).
 * The session is released through the forcing abort - the same path Cancel takes, which
 * guards a game still running so the next Launch cannot start a second one over it - and
 * home shows the outcome. Never kills the game; never quits the app (overlay off quits only
 * for a game that came up).
 */
function endLaunchWithOutcome(outcome) {
  if (sessionReset.isActive()) return;
  launchOutcome = { outcome, kind: lastLaunchKind };
  stopProgressPolling();
  sessionReset.resetToHome("launch_outcome");
}

/**
 * The lobby poll latched the game's end before the first live snapshot. If Cobblify never
 * came up, the game died early: the progress poll owns that verdict (Weave's log may say
 * wrong version), so ask it once before the ordinary reset.
 */
async function resetFromLobby(reason) {
  const gen = launchController.getState().launchGen;
  if (reason === "game_session_ended" && !firstLiveSeen && gen !== 0) {
    try {
      const p = await launchController.pollProgress();
      if (launchController.getState().launchGen !== gen) return;
      if (p?.outcome) {
        endLaunchWithOutcome(p.outcome);
        return;
      }
    } catch {
      // fall through to the ordinary reset
    }
  }
  return sessionReset.resetToHome(reason);
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
    firstLiveSeen,
    applyLobbySnapshot,
    resetToHome: (reason) => resetFromLobby(reason),
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
  if (ctx === "disconnected") {
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
  // Every setup run's progress (setup.rs); the snapshot is whole each time.
  window.__TAURI__.event.listen(SETUP_EVENT, ({ payload }) => onSetupSnapshot(payload));
}

// A setup that was under way (or failed) before this page loaded: catch up with it.
setupState(invoke)
  .then((snap) => {
    if (snap && snap.phase !== "done") onSetupSnapshot(snap, { fromReply: true });
  })
  .catch(() => {});

function canAutoRefresh() {
  if (previewing || suppressRefresh) return false;
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
