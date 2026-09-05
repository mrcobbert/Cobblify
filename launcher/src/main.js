import "@fontsource/unbounded/latin-700.css";
import "@fontsource/dm-mono/latin-400.css";
import "@fontsource/dm-mono/latin-500.css";
import "./style.css";

import { createHeroScene } from "./scene.js";
import { aliases, dashboardParty, opponentTeams, withoutPlayers } from "./roster-identity.js";
import {
  PREVIEW_SETUP_KEYS,
  PREVIEW_SETUP_LABELS,
  PREVIEW_STATUS,
  resolvePreviewStatus,
} from "./setup-fixtures.js";
import {
  PREVIEW_UPDATE,
  PREVIEW_UPDATE_KEYS,
  PREVIEW_UPDATE_LABELS,
  resolvePreviewUpdate,
} from "./update-fixtures.js";
import { createConnectionModel } from "./connection-state.js";
import { createConnectionShell } from "./connection-shell.js";
import { createLaunchController } from "./launch-controller.js";
import { createOverlayExit } from "./overlay-exit.js";
import { createSessionReset } from "./session-reset.js";
import { createUpdateController } from "./update-controller.js";
import { updateView } from "./update-view.js";
import { handleLobbyPoll as runLobbyPoll } from "./lobby-poll-handler.js";
import { nextLiveDashboard } from "./dashboard-view.js";
import {
  abortLaunchSession,
  quitApp,
  rehideAfterConfirmation,
  resetSessionEnd,
} from "./tauri-contract.js";
import { LOBBY_POLL_MS } from "./poll-config.js";
import {
  ISSUE,
  launchLabel,
  readyTargets,
  setupBlocks,
  topLevelError,
} from "./setup-view.js";
import { manualRouteSubtitleFor } from "./connection-view.js";
import {
  keepsStageNarration,
  launchViewRoute,
  showsCancelLaunch,
} from "./launch-view-route.js";

const el = (id) => document.getElementById(id);
const stage = el("stage");
const joining = el("joining");
const dash = el("dash");
const connAnnounce = el("conn-announce");
const autoJoinWrap = el("auto-join-wrap");
const autoJoinInput = el("auto-join");
const overlayWrap = el("overlay-wrap");
const overlayInput = el("use-overlay");
const prefError = el("pref-error");
const repairPref = el("repair-pref");
const cancelLaunch = el("cancel-launch");
const updateStrip = el("update-strip");
const updateTitle = el("update-title");
const updateDetail = el("update-detail");
const updatePrimary = el("update-primary");
const updateSecondary = el("update-secondary");
const updateProgress = el("update-progress");
const updateProgressFill = el("update-progress-fill");
const scene = createHeroScene(el("hero-canvas"));
// Scene boot (shader compile included) is behind us - let the entrance run.
stage.classList.add("lit");

function syncHomeLayout() {
  // The homepage keeps its defining cabinet composition at every supported
  // launcher size: copy on the left, voxel island on the right. The old stack
  // breakpoint centered the wordmark and moved the art above it in narrower or
  // taller windows, which made the launcher feel like a different screen.
  const next = "split";
  if (stage.dataset.layout === next) return;
  stage.dataset.layout = next;
  scene?.relayout?.();
}
new ResizeObserver(() => {
  syncHomeLayout();
  syncDashLayout();
}).observe(stage);
syncHomeLayout();

/**
 * The Tauri bridge is injected by the app shell (withGlobalTauri). Outside it -
 * `npm run preview` in a browser - fall back to a stub so the whole flow can be
 * watched without touching the real Lunar config or the mod's lobby.json.
 *   ?state=lunarReady|prismChoose|bothReady|…  picks the initial setup case
 *   ?update=consent|checking|available|critical|downloading|…  updater row
 *   ?ctx=lobby|joining|waiting|disconnected  lobby dashboard / joining / waiting
 *   ?ctx=preexisting_game|session_ended|session_changed|launch_aborted
 *   ?ctx=queueSolo|queueDoubles|queueThrees|queueFours
 *   ?ctx=gameSolo|gameDoubles|gameThrees|gameFours|game4v4
 */
const invoke = window.__TAURI__?.core?.invoke ?? previewInvoke;

// ── preview sample data ─────────────────────────────────────────────────────
const pp = (o) =>
  Object.assign(
    {
      state: "OK",
      nicked: false,
      realName: null,
      rank: "",
      fkdr: 0,
      wlr: 0,
      finalKills: 0,
      kd: 0,
      seraphThreat: -1,
      seraphTags: [],
      urchinTags: [],
    },
    o,
  );

const P = {
  you: pp({ name: "you_", rank: "[MVP+]", fkdr: 5.9, wlr: 3.8, finalKills: 15200, kd: 3.2 }),
  duo: pp({ name: "duo_diff", rank: "[VIP+]", fkdr: 7.2, wlr: 4.9, finalKills: 19000, kd: 3.9 }),
  third: pp({ name: "third_wheeler", rank: "[MVP]", fkdr: 3.1, wlr: 2.2, finalKills: 6400, kd: 2.4 }),
  clutch: pp({ name: "clutch_or_kick", rank: "[VIP]", fkdr: 1.8, wlr: 1.1, finalKills: 2100, kd: 1.4 }),
  shadow: pp({ name: "xXShadowSlayerXx", rank: "[MVP++]", fkdr: 22.7, wlr: 14.0, finalKills: 71200, kd: 8.9, seraphThreat: 4, seraphTags: ["REACH"], urchinTags: ["BLATANT"] }),
  wallhax: pp({ name: "wallhax_", rank: "[VIP]", fkdr: 19.1, wlr: 11.4, finalKills: 60300, kd: 7.7, urchinTags: ["GHOST"] }),
  tenko: pp({ name: "Tenko", rank: "[MVP++]", fkdr: 14.2, wlr: 9.1, finalKills: 48210, kd: 6.7, seraphThreat: 2 }),
  zenith: pp({ name: "ok_zenith", rank: "[MVP+]", fkdr: 8.4, wlr: 5.2, finalKills: 22140, kd: 4.1 }),
  prot: pp({ name: "Prot_IV_Diamond", rank: "[MVP+]", fkdr: 6.8, wlr: 4.4, finalKills: 17700, kd: 3.6 }),
  frost: pp({ name: "GodBridgeGod", nicked: true, realName: "Frostbyte_", rank: "[MVP]", fkdr: 4.6, wlr: 3.1, finalKills: 9800, kd: 2.8 }),
  moss: pp({ name: "mossling", rank: "[MVP]", fkdr: 3.3, wlr: 2.0, finalKills: 5600, kd: 2.2 }),
  ogre: pp({ name: "TheObsidianOgre", rank: "[MVP]", fkdr: 2.9, wlr: 1.8, finalKills: 4900, kd: 2.0 }),
  aqua: pp({ name: "aqua_gg", rank: "[VIP+]", fkdr: 2.1, wlr: 1.4, finalKills: 3120, kd: 1.6 }),
  virus: pp({ name: "notavirus_exe", rank: "[VIP+]", fkdr: 1.6, wlr: 1.2, finalKills: 2440, kd: 1.3, urchinTags: ["AUTOCLICK"] }),
  bread: pp({ name: "Bread_Enjoyer", rank: "[VIP]", fkdr: 1.05, wlr: 0.9, finalKills: 880, kd: 1.1 }),
  cool: pp({ name: "coolkid2013", rank: "", fkdr: 0.42, wlr: 0.5, finalKills: 120, kd: 0.7 }),
  nick: pp({ name: "iToxicWaffle", rank: "", state: "NICKED", nicked: true }),
  grandpa: pp({ name: "Grandpa_Joe", rank: "", state: "NEVER_PLAYED" }),
  lag: pp({ name: "lagswitch99", rank: "[VIP]", state: "LOADING" }),
  sniped: pp({ name: "sniped_u", rank: "", state: "LOADING" }),
};

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
      team("Red", [P.shadow, P.wallhax, P.tenko, P.zenith]),
      team("Blue", [P.you, P.duo, P.third, P.clutch]),
      team("Green", [P.frost, P.moss, P.cool, P.grandpa]),
      team("Yellow", [P.virus, P.bread, P.nick, P.prot]),
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
      health: "valid",
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

// ── initial status render (loading / ready / blocked / error) ───────────────
const setupBox = el("setup");
let setupBusy = false;
let lastStatus = { state: "loading", targets: [] };
let refreshTimer = null;
let suppressRefresh = false;

function render(status) {
  lastStatus = status;
  const ready = readyTargets(status);
  const hasReady = ready.length > 0;
  const err = topLevelError(status);
  const state = err
    ? "error"
    : hasReady
      ? "ready"
      : ["blocked", "error"].includes(status.state)
        ? status.state
        : "blocked";
  const version = status.mod_version ? `v${status.mod_version}` : null;

  el("chip-text").textContent =
    state === "ready" ? (version ?? "installed") : state === "loading" ? "checking setup" : (version ?? "—");

  const lunarReady = ready.some((t) => t.kind === "lunar");
  const forgeReady = ready.some((t) => t.kind === "forge");
  el("launch").hidden = !lunarReady;
  el("launch-forge").hidden = !forgeReady;
  launchController.projectLaunchButtons({ lunarReady, forgeReady });
  syncLaunchUi();

  renderSetup(status, state);
  stage.dataset.state = state;
  scene?.setMood(state);
}

function renderSetup(status, state) {
  const blocks = setupBlocks(status);
  const err = topLevelError(status);
  const showSetup = blocks.length > 0 || err;
  setupBox.hidden = !showSetup;
  setupBox.replaceChildren();

  if (err) {
    setupBox.append(setupErrorNode(err));
  }

  const compact = state === "ready";
  for (const block of blocks) {
    setupBox.append(setupBlockNode(block, compact || block.compact));
  }
}

function setupErrorNode(err) {
  const box = node("div", "setup-block");
  box.append(node("p", "setup-head", err.heading));
  const actions = node("div", "setup-actions");
  for (const action of err.actions) actions.append(setupActionButton(action));
  box.append(actions);
  if (err.detail) box.append(setupDetails(err.detail));
  return box;
}

function setupBlockNode(block, compact) {
  const box = node("div", compact ? "setup-block setup-block-compact" : "setup-block");
  box.append(node("p", "setup-head", block.heading));
  if (block.hint) box.append(node("p", "setup-hint", block.hint));

  if (block.candidates.length) {
    const list = node("ul", "setup-cands");
    for (const row of block.candidates) {
      const li = node("li", "setup-cand");
      li.append(node("span", "setup-cand-name", row.name));
      li.append(setupActionButton(row.action));
      list.append(li);
    }
    box.append(list);
  }

  if (block.actions.length) {
    const actions = node("div", "setup-actions");
    for (const action of block.actions) actions.append(setupActionButton(action));
    box.append(actions);
  }

  if (block.hasDetails && block.detailText) {
    box.append(setupDetails(block.detailText));
  }

  return box;
}

function setupDetails(text) {
  const det = document.createElement("details");
  det.className = "setup-details";
  const sum = document.createElement("summary");
  sum.textContent = "Details";
  det.append(sum, node("p", "setup-detail-text selectable", text));
  return det;
}

function setupActionButton(action) {
  const btn = node("button", action.kind === "setup_prism" ? "btn setup-btn" : "btn setup-btn", action.label);
  btn.type = "button";
  btn.dataset.action = action.kind;
  if (action.launcher) btn.dataset.launcher = action.launcher;
  if (action.candidateId) btn.dataset.candidateId = action.candidateId;
  btn.addEventListener("click", () => runSetupAction(action, btn));
  return btn;
}

async function runSetupAction(action, btn) {
  const st = launchController.getState();
  if (setupBusy || st.activeOutcome != null || st.launchBusy || st.prefSaving || st.prefUncertain) {
    return;
  }
  setupBusy = true;
  const prev = btn.textContent;
  if (action.kind === "setup_prism") {
    btn.disabled = true;
    btn.textContent = "Setting Up…";
    btn.setAttribute("aria-busy", "true");
  } else {
    for (const b of setupBox.querySelectorAll("button")) b.disabled = true;
  }
  try {
    if (action.kind === "refresh") {
      const next = await invoke("refresh_setup");
      if (next) render(next);
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
      if (next) render(next);
    }
  } catch (e) {
    setupBox.append(node("p", "alert", String(e)));
  } finally {
    setupBusy = false;
    for (const b of setupBox.querySelectorAll("button")) {
      b.disabled = false;
      b.removeAttribute("aria-busy");
    }
    if (action.kind === "setup_prism") btn.textContent = prev;
  }
}

function node(tag, cls, text) {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text != null) n.textContent = text;
  return n;
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
/** Launch kind whose button currently shows backend stage text, if any. */
let stageNarrationKind = null;

const launch = el("launch");
const launchForge = el("launch-forge");
const launchError = el("launch-error");

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
        launchController.tryAgainLaunch(lastLaunchKind, launchHooks);
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
    if (progressTimer) {
      clearInterval(progressTimer);
      progressTimer = null;
    }
    stageNarrationKind = null;
    launch.classList.remove("is-loading");
    launchForge.classList.remove("is-loading");
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
  onChange: syncLaunchUi,
});
let updateBlocksLaunch = false;
const updateController = createUpdateController(invoke, { onChange: syncUpdateUi });

function syncUpdateUi() {
  const snapshot = updateController.getState();
  const view = updateView(snapshot);
  updateTitle.textContent = view.title;
  updateDetail.textContent = view.detail;
  updateDetail.hidden = !view.detail;
  updatePrimary.hidden = !view.primaryAction;
  updatePrimary.textContent = view.primaryLabel ?? "";
  updatePrimary.dataset.action = view.primaryAction ?? "";
  updateSecondary.hidden = !view.secondaryAction;
  updateSecondary.textContent = view.secondaryLabel ?? "";
  updateSecondary.dataset.action = view.secondaryAction ?? "";
  const showProgress = ["downloading", "paused"].includes(snapshot.state) && snapshot.sizeBytes > 0;
  updateProgress.hidden = !showProgress;
  updateProgress.setAttribute("aria-hidden", showProgress ? "false" : "true");
  const percent = showProgress
    ? Math.min(100, Math.round((snapshot.downloadedBytes / snapshot.sizeBytes) * 100))
    : 0;
  updateProgressFill.style.width = `${percent}%`;
  updateBlocksLaunch = view.blocksLaunch;
  syncLaunchUi();
}

for (const button of [updatePrimary, updateSecondary]) {
  button.addEventListener("click", () => {
    const action = button.dataset.action;
    if (action) updateController.action(action);
  });
}

function applyPreviewUpdate(key) {
  previewUpdateKey = PREVIEW_UPDATE[key] ? key : "consent";
  updateController.acceptStatus(resolvePreviewUpdate(previewUpdateKey));
}

function syncLaunchUi() {
  const st = launchController.getState();
  const ready = readyTargets(lastStatus);
  const lunarReady = ready.some((t) => t.kind === "lunar");
  const forgeReady = ready.some((t) => t.kind === "forge");
  const buttons = launchController.projectForCurrentPhase({ lunarReady, forgeReady });
  // A re-render must never clobber live stage narration with the generic
  // phase label: the launch button IS the progress surface now.
  const narration = { launchPhase: st.launchPhase, narratingKind: stageNarrationKind };
  if (lunarReady && buttons.find((b) => b.kind === "lunar")) {
    const b = buttons.find((x) => x.kind === "lunar");
    if (!keepsStageNarration({ ...narration, kind: "lunar" })) {
      el("launch-label").textContent = b.label;
    }
    launch.disabled = !b.enabled || updateBlocksLaunch;
    launch.classList.toggle("is-loading", b.loading);
  }
  if (forgeReady && buttons.find((b) => b.kind === "forge")) {
    const b = buttons.find((x) => x.kind === "forge");
    if (!keepsStageNarration({ ...narration, kind: "forge" })) {
      el("launch-forge-label").textContent = b.label;
    }
    launchForge.disabled = !b.enabled || updateBlocksLaunch;
    launchForge.classList.toggle("is-loading", b.loading);
  }
  autoJoinWrap.hidden = !st.showAutoJoinSwitch;
  autoJoinInput.checked = st.optimisticAutoJoin;
  autoJoinInput.disabled = st.launchBusy || st.prefSaving || st.prefUncertain || st.activeOutcome != null;
  overlayWrap.hidden = !st.showAutoJoinSwitch;
  overlayInput.checked = st.optimisticOverlay;
  overlayInput.disabled = autoJoinInput.disabled;
  repairPref.hidden = st.prefHealth !== "invalid" && !st.prefUncertain;
  cancelLaunch.hidden = !showsCancelLaunch({
    activeOutcome: st.activeOutcome,
    launchPhase: st.launchPhase,
    homeUntilLive,
    resetActive: sessionReset.isActive(),
  });
  if (st.prefError) {
    prefError.textContent = st.prefError;
    prefError.hidden = false;
  } else {
    prefError.hidden = true;
  }
}

autoJoinInput.addEventListener("change", () => {
  launchController.setAutoJoin(autoJoinInput.checked);
});
overlayInput.addEventListener("change", () => {
  launchController.setOverlay(overlayInput.checked);
});
repairPref.addEventListener("click", () => {
  // Any save rewrites a valid two-key document (backend salvages the valid
  // sibling); an uncertain save must be repaired through its own key.
  if (launchController.getState().prefUncertainKey === "overlay") {
    launchController.setOverlay(overlayInput.checked, { repair: true });
  } else {
    launchController.setAutoJoin(autoJoinInput.checked, { repair: true });
  }
});

function stopProgressPolling() {
  if (progressTimer) {
    clearInterval(progressTimer);
    progressTimer = null;
  }
  stageNarrationKind = null;
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
    syncLaunchUi();
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
    launch.classList.remove("is-loading");
    launchForge.classList.remove("is-loading");
    launchError.hidden = true;
    launchController.resetLaunchSession(prefs);
    stage.dataset.view = "home";
    syncLaunchUi();
    updateController.setGameActive(false);
  },
  refreshHome: async () => {
    const next = await invoke("refresh_setup");
    if (next) render(next);
  },
  onRefreshError: (e) => {
    // Home stays visible with the existing setup-error rendering (the
    // same projection the boot path uses) - recoverable, never silent.
    render(setupErrorStatus(e));
  },
  showTransitional: (reason) => {
    connectionShell.enterTerminal(resetShellMode(reason, "transitional"));
    // Only a session that actually reached the dashboard shows this copy
    // there. A pre-live quit or a cancelled launch never left home
    // (`homeUntilLive` is still set): it stays on home, buttons re-enabling
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

function startProgressPolling(kind, autoJoin = true) {
  stopProgressPolling();
  const labelEl = kind === "forge" ? el("launch-forge-label") : el("launch-label");
  const labels = launchController.getState().stageLabels(autoJoin);
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
        if (labels[p.stage]) {
          labelEl.textContent = labels[p.stage];
          stageNarrationKind = kind;
        }
        if (p.stage === "settled" || p.stage === "forge_settled") {
          // Both auto-join states settle on the home button now.
          launchController.settleLaunchPhase();
          syncLaunchUi();
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
    launchError.hidden = true;
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
    syncLaunchUi();
  },
  onProgressStart(autoJoin = true) {
    startProgressPolling(lastLaunchKind, autoJoin);
  },
};

launch.addEventListener("click", () => {
  lastLaunchKind = "lunar";
  suppressRefresh = true;
  launchController.launch("lunar", launchHooks);
});
launchForge.addEventListener("click", () => {
  lastLaunchKind = "forge";
  suppressRefresh = true;
  launchController.launch("forge", launchHooks);
});
// The pre-live escape hatch. The backend has nothing to latch on yet, so
// this takes the forcing abort; the orchestrator handles the rest exactly
// like any other return to home.
cancelLaunch.addEventListener("click", () => {
  sessionReset.resetToHome("launch_aborted");
});

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
    } catch {
      return;
    }
  }
  dash.classList.add("on");
}

function applyLobby(d) {
  applyLobbySnapshot(d);
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
const CHEAT_TAG = /BLATANT|GHOST|REACH|AUTOCLICK/i;

function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}
const n2 = (v) => (typeof v === "number" && isFinite(v) ? v.toFixed(2) : "—");
const nInt = (v) => (typeof v === "number" && isFinite(v) ? Math.round(v).toLocaleString() : "—");
const tier = (f) => (f >= 10 ? 3 : f >= 6 ? 2 : f >= 3 ? 1 : 0);

function isCheater(p) {
  const seraphTags = p.seraphTags ?? [];
  const urchinTags = p.urchinTags ?? [];
  return (
    (typeof p.seraphThreat === "number" && p.seraphThreat >= 3) ||
    urchinTags.length > 0 ||
    seraphTags.some((t) => CHEAT_TAG.test(t))
  );
}

// OK players high→low by FKDR; unresolved states (loading/nick/never/error)
// keep their given order at the bottom.
function bySweat(players) {
  const list = (players ?? []).slice();
  const ranked = list.filter((p) => p.state === "OK").sort((a, b) => (b.fkdr ?? 0) - (a.fkdr ?? 0));
  const rest = list.filter((p) => p.state !== "OK");
  return ranked.concat(rest);
}

function pname(p, chips) {
  const flags = chips && chips.length ? `<span class="flags-inline">${chips.join("")}</span>` : "";
  return `<div class="pname"><span class="sweat-bar"></span>
    <div class="identity">${rankHtml(p.rank)}<span class="pn">${esc(p.name)}</span></div>${flags}</div>`;
}

function rankHtml(rank) {
  const raw = String(rank ?? "").trim();
  if (!raw) return "";
  const inner = raw.replace(/^\[/, "").replace(/\]$/, "");
  const tag = (html) => `<span class="rank">${html}</span>`;
  switch (inner.toUpperCase()) {
    case "MVP++":
      return tag(`<span class="r-gold">[MVP<span class="r-red">++</span>]</span>`);
    case "MVP+":
      return tag(`<span class="r-aqua">[MVP+]</span>`);
    case "MVP":
      return tag(`<span class="r-aqua">[MVP]</span>`);
    case "VIP+":
      return tag(`<span class="r-green">[VIP<span class="r-gold">+</span>]</span>`);
    case "VIP":
      return tag(`<span class="r-green">[VIP]</span>`);
    case "YOUTUBE":
      return tag(`<span class="r-red">[<span class="r-white">YOUTUBE</span>]</span>`);
    case "ADMIN":
      return tag(`<span class="r-red">[ADMIN]</span>`);
    case "OWNER":
      return tag(`<span class="r-red">[OWNER]</span>`);
    case "MOJANG":
      return tag(`<span class="r-gold">[MOJANG]</span>`);
    case "GM":
      return tag(`<span class="r-darkgreen">[GM]</span>`);
    case "MOD":
      return tag(`<span class="r-darkgreen">[MOD]</span>`);
    case "HELPER":
      return tag(`<span class="r-blue">[HELPER]</span>`);
    default:
      return tag(esc(raw));
  }
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

function row(p, teamName) {
  const teamCls = teamClass(teamName);
  if (p.state === "LOADING") {
    return `<div class="prow loading${teamCls}">
      ${pname(p, ['<span class="chip state">resolving</span>'])}
      <div class="stat fkdr"><span class="skel" style="width:28px"></span></div>
      <div class="stat cell-wlr"><span class="skel" style="width:24px"></span></div>
      <div class="stat cell-finals"><span class="skel" style="width:34px"></span></div>
      <div class="stat cell-kd"><span class="skel" style="width:22px"></span></div></div>`;
  }

  if (p.state === "NEVER_PLAYED" || p.state === "ERROR") {
    const lbl = p.state === "ERROR" ? "error" : "never played";
    return `<div class="prow${teamCls}">
      ${pname(p, [`<span class="chip state">${lbl}</span>`])}
      <div class="stat muted fkdr">—</div><div class="stat muted cell-wlr">—</div>
      <div class="stat muted cell-finals">—</div><div class="stat muted cell-kd">—</div></div>`;
  }

  if (p.state === "NICKED" && !p.realName) {
    return `<div class="prow${teamCls}">
      ${pname(p, ['<span class="chip nick">nick</span>'])}
      <div class="stat muted fkdr">?</div><div class="stat muted cell-wlr">?</div>
      <div class="stat muted cell-finals">?</div><div class="stat muted cell-kd">?</div></div>`;
  }

  const t = tier(p.fkdr ?? 0);
  const cheat = isCheater(p);
  const chips = [];
  if (p.nicked && p.realName) chips.push(`<span class="chip nick">nick→${esc(p.realName)}</span>`);
  (p.seraphTags ?? []).forEach((s) => chips.push(`<span class="chip cheat">${esc(s)}</span>`));
  (p.urchinTags ?? []).forEach((s) => chips.push(`<span class="chip cheat">${esc(s)}</span>`));

  return `<div class="prow t${t} ${cheat ? "cheater" : ""}${teamCls}">
    ${pname(p, chips)}
    <div class="stat fkdr">${n2(p.fkdr)}</div>
    <div class="stat cell-wlr">${n2(p.wlr)}</div>
    <div class="stat cell-finals">${nInt(p.finalKills)}</div>
    <div class="stat cell-kd">${n2(p.kd)}</div></div>`;
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
      return `<div class="col">${labels}${col.map((u) => u.html).join("")}</div>`;
    })
    .join("");
  if (view.bare) {
    return `<div class="sheet bare" style="--n:${k}">${body}</div>`;
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
  return `<div class="sheet" style="--n:${k}">${head}${body}</div>`;
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

function partyBlock(list, teamOf) {
  if (!list || !list.length) return null;
  return {
    head: sectHead("Your Party", list.length),
    rows: list.map((p) => row(p, teamNameFor(teamOf, p))),
  };
}

function teamAvg(t) {
  const ok = (t.players ?? []).filter((p) => p.state === "OK");
  return ok.length ? ok.reduce((s, p) => s + (p.fkdr ?? 0), 0) / ok.length : null;
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
  const teams = opponentTeams(d)
    .map((t) => ({ t, players: t.players, agg: teamAvg(t) }))
    .sort((a, b) => (b.agg ?? -1) - (a.agg ?? -1));
  const maxAgg = teams.length ? teams[0].agg : null;
  const blocks = teams.map(({ t, players, agg }) => {
    const target = maxAgg !== null && agg === maxAgg;
    const rest = bySweat(players);
    const slug = TEAM_SLUG[t.name];
    const teamCls = slug ? ` team-${slug}` : "";
    // Atomic: a team's border, colour scope and rows are placed as one unit. The
    // team is named by its border colour alone; the rail breaks that border to
    // carry the average, and the target flag on the sweatiest team.
    return {
      n: rest.length,
      atomic: `<div class="team${teamCls}"><div class="team-rail">
        <span class="agg">avg FKDR ${agg === null ? "—" : agg.toFixed(1)}</span>
        ${target ? '<span class="target-badge">Target</span>' : ""}</div>
        ${roster(rest, teamOf)}</div>`,
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
const bootKind = bootParams.get("ctx") || bootParams.get("state");

function afterStatus(status) {
  render(status);
  if (!previewing) launchController.refreshPreferences();
  if (!previewing) return;
  mountPreviewBar(bootParams.get("update") || bootKind || "lunarReady");
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

if (previewing && bootParams.get("state") === "loading") {
  mountPreviewBar("loading");
} else {
  invoke("refresh_setup")
    .then(afterStatus)
    .catch((e) => afterStatus(setupErrorStatus(e)));
}

if (previewing) {
  applyPreviewUpdate(bootParams.get("update") || "consent");
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
  if (launch.classList.contains("is-loading") || launchForge.classList.contains("is-loading")) {
    return false;
  }
  return true;
}

function scheduleRefresh() {
  if (!canAutoRefresh()) return;
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(async () => {
    if (!canAutoRefresh()) return;
    try {
      const next = await invoke("refresh_setup");
      if (next) render(next);
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

// Browser-only chrome: a 900×620 frame plus a bar to flip every UI state.
const PREVIEW_TOUR_MS = 1000;
const PREVIEW_TOUR_KEYS = [
  "loading",
  ...PREVIEW_SETUP_KEYS,
  "lunarReady",
  "lunarReadyOff",
  "overlayOff",
  "forgeReadyOff",
  "bothReady",
  "invalidPref",
  ...PREVIEW_UPDATE_KEYS,
  "lobby",
  "joining",
  "disconnected",
  "waiting",
  "preexisting_game",
  "session_ended",
  "session_changed",
  "launch_aborted",
  "session_ended_resetting",
  "queueSolo",
  "queueDoubles",
  "queueThrees",
  "queueFours",
  "gameSolo",
  "gameDoubles",
  "gameThrees",
  "gameFours",
  "game4v4",
];

let previewTourTimer = null;
let previewTourIndex = -1;

function syncPreviewPlayButton(playing) {
  const btn = document.getElementById("preview-play");
  if (!btn) return;
  btn.classList.toggle("on", playing);
  btn.textContent = playing ? "■" : "▶";
  btn.setAttribute("aria-label", playing ? "Stop demo tour" : "Play through every state");
  btn.title = playing ? "Stop demo tour" : "Play through every state (1s each)";
}

function stopPreviewTour() {
  if (previewTourTimer != null) {
    clearInterval(previewTourTimer);
    previewTourTimer = null;
  }
  previewTourIndex = -1;
  syncPreviewPlayButton(false);
}

function startPreviewTour() {
  stopPreviewTour();
  previewTourIndex = 0;
  syncPreviewPlayButton(true);
  showPreview(PREVIEW_TOUR_KEYS[0]);
  previewTourTimer = setInterval(() => {
    previewTourIndex += 1;
    if (previewTourIndex >= PREVIEW_TOUR_KEYS.length) {
      stopPreviewTour();
      return;
    }
    showPreview(PREVIEW_TOUR_KEYS[previewTourIndex]);
  }, PREVIEW_TOUR_MS);
}

function togglePreviewTour() {
  if (previewTourTimer != null) stopPreviewTour();
  else startPreviewTour();
}

function mountPreviewBar(active) {
  if (document.getElementById("preview-bar")) {
    markPreviewActive(active);
    mountPreviewWindow();
    return;
  }
  const bar = document.createElement("nav");
  bar.id = "preview-bar";
  bar.className = "preview-bar";
  const setupButtons = PREVIEW_SETUP_KEYS.map(
    (key) =>
      `<button type="button" data-preview="${key}">${PREVIEW_SETUP_LABELS[key] ?? key}</button>`,
  ).join("");
  const updateButtons = PREVIEW_UPDATE_KEYS.map(
    (key) =>
      `<button type="button" data-preview="${key}">${PREVIEW_UPDATE_LABELS[key] ?? key}</button>`,
  ).join("");
  bar.innerHTML = `
    <button type="button" id="preview-play" class="preview-play" title="Play through every state (1s each)" aria-label="Play through every state">▶</button>
    <span class="preview-size">900×620</span>
    <span class="preview-group">setup</span>
    <button type="button" data-preview="loading">loading</button>
    ${setupButtons}
    <span class="preview-group">ready</span>
    <button type="button" data-preview="lunarReady">lunar on</button>
    <button type="button" data-preview="lunarReadyOff">lunar off</button>
    <button type="button" data-preview="overlayOff">overlay off</button>
    <button type="button" data-preview="forgeReadyOff">prism off</button>
    <button type="button" data-preview="bothReady">both</button>
    <button type="button" data-preview="invalidPref">bad pref</button>
    <span class="preview-group">update</span>
    ${updateButtons}
    <span class="preview-group">lobby</span>
    <button type="button" data-preview="lobby">lobby</button>
    <button type="button" data-preview="joining">joining</button>
    <button type="button" data-preview="disconnected">disconnected</button>
    <span class="preview-group">session</span>
    <button type="button" data-preview="waiting">waiting</button>
    <button type="button" data-preview="preexisting_game">already running</button>
    <button type="button" data-preview="session_ended">ended</button>
    <button type="button" data-preview="session_changed">changed</button>
    <button type="button" data-preview="launch_aborted">cancelled</button>
    <button type="button" data-preview="session_ended_resetting">returning</button>
    <span class="preview-group">queue</span>
    <button type="button" data-preview="queueSolo">solos</button>
    <button type="button" data-preview="queueDoubles">doubles</button>
    <button type="button" data-preview="queueThrees">3s</button>
    <button type="button" data-preview="queueFours">4s</button>
    <span class="preview-group">game</span>
    <button type="button" data-preview="gameSolo">solos</button>
    <button type="button" data-preview="gameDoubles">doubles</button>
    <button type="button" data-preview="gameThrees">3s</button>
    <button type="button" data-preview="gameFours">4s</button>
    <button type="button" data-preview="game4v4">4v4</button>`;
  bar.addEventListener("click", (e) => {
    if (e.target.closest("#preview-play")) {
      togglePreviewTour();
      return;
    }
    const btn = e.target.closest("[data-preview]");
    if (btn) {
      stopPreviewTour();
      showPreview(btn.dataset.preview);
    }
  });
  document.body.prepend(bar);
  markPreviewActive(active);
  mountPreviewWindow();
}

const PREVIEW_DEFAULT_W = 900;
const PREVIEW_DEFAULT_H = 620;
const PREVIEW_MIN_W = 640;
const PREVIEW_MIN_H = 480;
const PREVIEW_MOVE_H = 26;

function mountPreviewWindow() {
  if (document.getElementById("preview-window")) return;

  const win = document.createElement("div");
  win.id = "preview-window";
  win.className = "preview-window";

  const move = document.createElement("div");
  move.className = "preview-move";
  move.innerHTML = `<span>move</span><span class="preview-size">${PREVIEW_DEFAULT_W}×${PREVIEW_DEFAULT_H}</span>`;
  move.title = "Drag to move · double-click to reset";

  const resize = document.createElement("button");
  resize.type = "button";
  resize.className = "preview-resize";
  resize.title = "Drag to resize";
  resize.setAttribute("aria-label", "Resize window");

  stage.replaceWith(win);
  win.append(move, stage, resize);

  let w = PREVIEW_DEFAULT_W;
  let h = PREVIEW_DEFAULT_H;
  let x = 0;
  let y = 0;

  function barHeight() {
    return document.getElementById("preview-bar")?.offsetHeight ?? 0;
  }

  function clampSize(nw, nh) {
    const maxW = Math.max(PREVIEW_MIN_W, window.innerWidth - 32);
    const maxH = Math.max(PREVIEW_MIN_H, window.innerHeight - barHeight() - PREVIEW_MOVE_H - 24);
    return {
      w: Math.round(Math.min(maxW, Math.max(PREVIEW_MIN_W, nw))),
      h: Math.round(Math.min(maxH, Math.max(PREVIEW_MIN_H, nh))),
    };
  }

  function clampPos(nx, ny, nw, nh) {
    const minY = barHeight() + PREVIEW_MOVE_H + 8;
    const maxX = window.innerWidth - nw - 12;
    const maxY = window.innerHeight - nh - 12;
    return {
      x: Math.round(Math.min(Math.max(12, nx), Math.max(12, maxX))),
      y: Math.round(Math.min(Math.max(minY, ny), Math.max(minY, maxY))),
    };
  }

  function apply() {
    win.style.width = `${w}px`;
    win.style.height = `${h}px`;
    win.style.left = `${x}px`;
    win.style.top = `${y}px`;
    document.querySelectorAll(".preview-size").forEach((node) => {
      node.textContent = `${w}×${h}`;
    });
    const ctx = dash.dataset.ctx;
    if (ctx) fitDash();
  }

  function centerDefault() {
    ({ w, h } = clampSize(PREVIEW_DEFAULT_W, PREVIEW_DEFAULT_H));
    const pos = clampPos((window.innerWidth - w) / 2, (window.innerHeight - h) / 2, w, h);
    x = pos.x;
    y = pos.y;
    apply();
  }

  function drag(onMove) {
    return (e) => {
      if (e.button !== 0) return;
      e.preventDefault();
      const pointer = e.currentTarget;
      pointer.setPointerCapture(e.pointerId);
      document.body.classList.add("is-dragging");
      const sx = e.clientX;
      const sy = e.clientY;
      const start = onMove.start();
      const movePtr = (ev) => onMove.move(ev.clientX - sx, ev.clientY - sy, start);
      const up = () => {
        pointer.releasePointerCapture(e.pointerId);
        pointer.removeEventListener("pointermove", movePtr);
        pointer.removeEventListener("pointerup", up);
        pointer.removeEventListener("pointercancel", up);
        document.body.classList.remove("is-dragging");
        win.classList.remove("is-resizing");
      };
      pointer.addEventListener("pointermove", movePtr);
      pointer.addEventListener("pointerup", up);
      pointer.addEventListener("pointercancel", up);
    };
  }

  move.addEventListener(
    "pointerdown",
    drag({
      start: () => ({ x, y }),
      move: (dx, dy, start) => {
        const pos = clampPos(start.x + dx, start.y + dy, w, h);
        x = pos.x;
        y = pos.y;
        apply();
      },
    }),
  );
  move.addEventListener("dblclick", centerDefault);

  resize.addEventListener(
    "pointerdown",
    (e) => {
      win.classList.add("is-resizing");
      drag({
        start: () => ({ w, h }),
        move: (dx, dy, start) => {
          ({ w, h } = clampSize(start.w + dx, start.h + dy));
          const pos = clampPos(x, y, w, h);
          x = pos.x;
          y = pos.y;
          apply();
        },
      })(e);
    },
  );

  window.addEventListener("resize", () => {
    ({ w, h } = clampSize(w, h));
    const pos = clampPos(x, y, w, h);
    x = pos.x;
    y = pos.y;
    apply();
  });

  centerDefault();
}

function markPreviewActive(kind) {
  document.querySelectorAll(".preview-bar [data-preview]").forEach((btn) => {
    btn.classList.toggle("on", btn.dataset.preview === kind);
  });
}

function resetPreviewSession() {
  stopProgressPolling();
  stopLobbyPolling();
  sessionReset.cancelRetry();
  overlayExit.resetSession();
  lastKey = null;
  lastView = null;
  firstLiveSeen = false;
  homeUntilLive = false;
  previewLaunchAt = null;
  setupBusy = false;
  suppressRefresh = false;
  connection.reset();
  connectionShell.reset();
  connAnnounce.textContent = "";
  dash.classList.remove("on");
  dash.replaceChildren();
  delete dash.dataset.ctx;
  delete dash.dataset.density;
  delete dash.dataset.teams;
  stage.dataset.view = "home";
  launch.disabled = false;
  launch.classList.remove("is-loading");
  launchForge.disabled = false;
  launchForge.classList.remove("is-loading");
  launchError.hidden = true;
  prefError.hidden = true;
  prefError.textContent = "";
  syncLaunchUi();
}

function showPreview(kind) {
  resetPreviewSession();
  const params = new URLSearchParams();
  if (kind === "loading") {
    stage.dataset.state = "loading";
    el("chip-text").textContent = "checking setup";
    params.set("state", "loading");
  } else if (kind === "joining") {
    render(PREVIEW_STATUS.lunarReady);
    stage.dataset.view = "dash";
    connectionShell.show("joining");
    params.set("ctx", "joining");
  } else if (kind === "waiting") {
    render(PREVIEW_STATUS.lunarReady);
    stage.dataset.view = "dash";
    connectionShell.show("waiting");
    params.set("ctx", "waiting");
  } else if (kind === "disconnected") {
    render(PREVIEW_STATUS.lunarReady);
    stage.dataset.view = "dash";
    connectionShell.enterDisconnected();
    params.set("ctx", "disconnected");
  } else if (kind === "preexisting_game") {
    render(PREVIEW_STATUS.lunarReady);
    stage.dataset.view = "dash";
    connectionShell.enterTerminal("preexisting_game", { showTryAgain: true });
    params.set("ctx", "preexisting_game");
  } else if (kind === "invalidPref") {
    render(PREVIEW_STATUS.lunarReady);
    autoJoinWrap.hidden = false;
    autoJoinInput.checked = true;
    overlayWrap.hidden = false;
    overlayInput.checked = true;
    repairPref.hidden = false;
    prefError.textContent = "Preference file is invalid.";
    prefError.hidden = false;
    params.set("state", "lunarReady");
  } else if (kind === "lunarReadyOff" || kind === "forgeReadyOff") {
    render(
      kind === "lunarReadyOff" ? PREVIEW_STATUS.lunarReady : PREVIEW_STATUS.forgeReady,
    );
    autoJoinWrap.hidden = false;
    autoJoinInput.checked = false;
    overlayWrap.hidden = false;
    overlayInput.checked = true;
    params.set("state", kind === "lunarReadyOff" ? "lunarReady" : "forgeReady");
  } else if (kind === "overlayOff") {
    render(PREVIEW_STATUS.lunarReady);
    autoJoinWrap.hidden = false;
    autoJoinInput.checked = true;
    overlayWrap.hidden = false;
    overlayInput.checked = false;
    params.set("state", "lunarReady");
  } else if (kind === "bothReady") {
    render(PREVIEW_STATUS.bothReady);
    autoJoinWrap.hidden = false;
    autoJoinInput.checked = true;
    overlayWrap.hidden = false;
    overlayInput.checked = true;
    params.set("state", "bothReady");
  } else if (
    kind === "session_ended" ||
    kind === "session_changed" ||
    kind === "launch_aborted" ||
    kind === "session_ended_resetting"
  ) {
    render(PREVIEW_STATUS.lunarReady);
    stage.dataset.view = "dash";
    connectionShell.enterTerminal(kind);
    params.set("ctx", kind);
  } else if (PREVIEW_UPDATE[kind]) {
    render(PREVIEW_STATUS.lunarReady);
    autoJoinWrap.hidden = false;
    autoJoinInput.checked = true;
    overlayWrap.hidden = false;
    overlayInput.checked = true;
    applyPreviewUpdate(kind);
    params.set("state", "lunarReady");
    params.set("update", kind);
  } else if (PREVIEW_LOBBY[kind]) {
    render(PREVIEW_STATUS.lunarReady);
    applyLobbySnapshot(PREVIEW_LOBBY[kind]);
    params.set("ctx", kind);
  } else {
    render(resolvePreviewStatus(kind));
    params.set("state", kind);
  }
  history.replaceState(null, "", `${location.pathname}?${params}`);
  markPreviewActive(kind);
}
