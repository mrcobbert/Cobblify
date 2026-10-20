import "./ui/fonts.css";
import "./ui/tokens.css";
import "./dash.css";
import "./ui/app.css";

import { PREVIEW_STATUS, resolvePreviewStatus } from "./setup-fixtures.js";
import { PREVIEW_LOBBY } from "./board-fixtures.js";
import { PREVIEW_UPDATE, resolvePreviewUpdate } from "./update-fixtures.js";
import { createConnectionModel } from "./connection-state.js";
import { createConnectionShell } from "./connection-shell.js";
import { createLaunchController } from "./launch-controller.js";
import { createOverlayExit } from "./overlay-exit.js";
import { createSessionReset } from "./session-reset.js";
import { createUpdateController } from "./update-controller.js";
import { handleLobbyPoll as runLobbyPoll } from "./lobby-poll-handler.js";
import { nextLiveDashboard } from "./dashboard-view.js";
import { boardView } from "./board-model.js";
import { createBoard, subHTML } from "./board-render.js";
import {
  SETUP_EVENT,
  abortLaunchSession,
  beginSetup,
  gameTheme,
  lunarVersionReady,
  openInfo,
  quitApp,
  resetSessionEnd,
  resumeSetup,
  setupState,
} from "./tauri-contract.js";
import { LOBBY_POLL_MS } from "./poll-config.js";
import { installWindowChrome } from "./window-chrome.js";
import { manualRouteSubtitleFor } from "./connection-view.js";
import { launchViewRoute } from "./launch-view-route.js";
import { createApp } from "./ui/app.js";
import { CL, uiId } from "./ui/art.js";
import { NEW_PLAYER_THEME, themeFor } from "./ui/accent.js";
import { setAccent, setFont, watchUnits, wordmark } from "./ui/kit.js";
import { homeModel, isInstalled, isSetUp, prismInstances, setUpClients, sheetModel, stuckAfter } from "./ui/model.js";

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
 *   ?ctx=preexisting_game|session_ended|session_changed|launch_aborted (+ _resetting)|join|manual
 *   ?ctx=queueSolo|queueDoubles|queueThrees|queueFours
 *   ?ctx=gameSolo|gameDoubles|gameThrees|gameFours|game4v4
 *   ?open=updated|fresh&version=…  what open_info answers
 *   ?accent=<token>&font=minecraft|modern  what game_theme answers
 *   ?setup=signin|game|fail  a preview setup stops at sign-in / Close Minecraft, or fails once at check 5
 */
const invoke = window.__TAURI__?.core?.invoke ?? previewInvoke;
const chromeReady = window.__TAURI__ ? installWindowChrome(window, invoke) : Promise.resolve();

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

  if (command === "lunar_version_ready") {
    return Promise.resolve("unknown");
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

  if (command === "quit_app") {
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
      // The preview's game "loads" Cobblify as it settles (the real backend's `loaded`).
      if (t >= ms) return Promise.resolve({ stage: `${prefix}${s}`, percent, ...(s === "settled" ? { loaded: true } : {}) });
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
  select: (id) => {
    if (launchOutcome && launchOutcome.kind !== CL[id]?.kind) launchOutcome = null;
    selected = id;
    freshSetup = false;
    // picking a set-up client drops Add's wait: Launch is back for it
    addWait = null;
    storeClient(id);
    refreshUi();
  },
  // Choose Your Setup's installed row; settings Repair; the add row of an installed client.
  setupNow: (id) => addClient(id),
  // Choose Your Setup's row for a client that isn't installed: its download page, then wait for the install.
  // Add's wait: the missing client's row opens its download page again and keeps waiting.
  get: (id) => (addWait === id ? openDownload(id) : awaitInstall(id)),
  add: (desc) => {
    if (!desc) return;
    if (desc.installed) {
      addClient(desc.id);
      return;
    }
    // Settings' Add before anything is set up: the first run's own wait.
    if (!setUpClients(lastStatus).length) {
      awaitInstall(desc.id);
      return;
    }
    addWait = desc.id;
    // Launch is paused while it waits: "Set Lunar to 1.8.9" must not relaunch by itself under it.
    if (launchOutcome?.outcome === "wrong_version") launchOutcome = null;
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

/**
 * An idle home (proto idleHome): Launch (or Add's wait) showing, nothing running, no sheet, no What's New, no setup
 * or pick.
 */
function homeIdle() {
  const s = ui.state;
  return (
    !!lastHome &&
    (lastHome.hero.kind === "play" || (addWait != null && lastHome.hero.kind === "setup")) &&
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
    inGame: gameUp,
    waiting: progressWaiting,
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
  el("dash-dev").hidden = update.updateChannel !== "dev";
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
async function showWindow() {
  if (windowShown) return;
  windowShown = true;
  await chromeReady;
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
 * Every open: the island's build replay, then What's New after an update. Once per session, on its first ready home.
 * A temporary startup check or blocked client must not consume the replay. A new player with no island instead
 * sees the normal setup build; after an update, keep waiting for a ready home so What's New is not lost.
 */
function maybeOpen(home) {
  if (openPlayed || !openInfoReply || document.hidden || lastStatus.state === "loading" || home.launching) return;
  if (!openInfoReply.updated && home.solo && home.hero?.kind === "setup") {
    openPlayed = true;
    return;
  }
  openPlayed = ui.open(openInfoReply) === true;
}
// A native window starts hidden. Keep the replay pending until it is visible
// AND ready; startup checks may change eligibility before the reveal.
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && lastHome) maybeOpen(lastHome);
});

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
// opens once Cobblify reports in (progress `loaded`, or an acknowledged
// snapshot of this game), asking the player to join Hypixel until a live
// snapshot draws the board - never from cosmetic progress alone. A progress `outcome` (wrong Lunar version,
// Cobblify didn't load) ends the launch back on home with that outcome.
const PROGRESS_POLL_MS = 600;
let progressTimer = null;
let lobbyTimer = null;
let lobbyPollInFlight = false;
let lastKey = null;
let firstLiveSeen = false;
let lastLaunchKind = "lunar";
/** Route-specific manual subtitle preserved across unavailable polls. */
let manualRouteSubtitle = null;
/**
 * Hold the home view (button narration) until Cobblify reports in from
 * this launch's game (enterGame). Set for every confirmed game dispatch; see
 * launch-view-route.js for the decision table.
 */
let homeUntilLive = false;
/** Cobblify reported in from this launch's game (`loaded`): the window is the dashboard. */
let gameUp = false;
/** No game yet ~15 s after the click, Lunar/Prism still up (launch_progress `waiting`). */
let progressWaiting = false;
/** Overlay off with a confirmed game: no dashboard at all, Cobblify quits once it loaded (overlay-exit.js). */
let noDashboard = false;

const boardUi = createBoard({ stage, board: dash, title: el("dash-title"), joining });
const joiningSub = el("joining-sub");
const connActionKey = el("conn-action");
const connActionSlot = el("conn-act");

const connection = createConnectionModel();
const connectionShell = createConnectionShell({
  titleEl: el("joining-title"),
  // The sub line draws the glyphs the pixel font lacks and steps the dots of "Returning home…".
  subEl: {
    get textContent() {
      return joiningSub.dataset.text ?? "";
    },
    set textContent(text) {
      joiningSub.dataset.text = text;
      joiningSub.innerHTML = subHTML(text);
    },
  },
  shellEl: joining,
  // The key sits in its own slot (the gap above it): the slot shows and hides with it.
  actionEl: {
    get hidden() {
      return connActionKey.hidden;
    },
    set hidden(on) {
      connActionKey.hidden = on;
      connActionSlot.hidden = on;
    },
    get textContent() {
      return connActionKey.textContent;
    },
    set textContent(text) {
      connActionKey.textContent = text;
    },
    addEventListener: (...args) => connActionKey.addEventListener(...args),
  },
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
    clearBoard();
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

// Overlay-off quit coordination (dependency-injected module; see
// overlay-exit.js for the exhaustive route table).
const overlayExit = createOverlayExit({
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
  launch_outcome: ["session_ended_resetting", "session_ended"],
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
    // The reset owns the surface from here: the launch key gives way
    // before its own command has even answered.
    refreshUi();
  },
  commitHome: (prefs) => {
    overlayExit.resetSession();
    connection.reset();
    connectionShell.reset();
    connAnnounce.textContent = "";
    clearBoard();
    firstLiveSeen = false;
    manualRouteSubtitle = null;
    homeUntilLive = false;
    gameUp = false;
    noDashboard = false;
    progressWaiting = false;
    suppressRefresh = false;
    progressStage = null;
    launchController.resetLaunchSession(prefs);
    stage.dataset.view = "home";
    refreshUi();
    updateController.setGameActive(false);
    // The launch ended: an add-wait follows through on the idle home.
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
        const waiting = p.waiting === true;
        if (p.stage !== progressStage || waiting !== progressWaiting) {
          progressStage = p.stage;
          progressWaiting = waiting;
          refreshUi();
        }
        if (p.stage === "settled" || p.stage === "forge_settled") {
          launchController.settleLaunchPhase();
        }
      }
      if (p?.loaded === true) {
        // The backend's proof that Cobblify loaded in this launch's game: the
        // overlay-off quit. The module quits exactly once across duplicate polls.
        overlayExit.onConfirmationSignal("loaded");
        enterGame();
      }
    } catch {
      // cosmetic fail-soft
    } finally {
      progressPollInFlight = false;
    }
  }, PROGRESS_POLL_MS);
}

const launchHooks = {
  onWrongVersion() {
    launchOutcome = { outcome: "wrong_version", kind: lastLaunchKind };
    watchLunarVersion();
    refreshUi();
  },
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
    noDashboard = exitRoute === "no_dashboard";
    gameUp = false;
    if (exitRoute === "quit_now") {
      // Never-fired open-only with overlay off: the backend brought the
      // launcher forward - exit immediately after the reply. (A failed
      // re-present arrives as launch_unconfirmed instead.)
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
    // session_ended poll can still reset the app to a launchable home; it
    // quits once Cobblify is known to have loaded (overlay-exit.js).
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
  progressWaiting = false;
  launchOutcome = null;
  freshSetup = false;
  const run = retry ? launchController.tryAgainLaunch(kind, launchHooks) : launchController.launch(kind, launchHooks);
  return run.finally(releaseRefreshUnlessLive);
}

/**
 * The launch ended without Cobblify: the backend's verdict (`wrong_version` / `not_loaded`).
 * The session is released through the forcing abort, which
 * guards a game still running so the next Launch cannot start a second one over it - and
 * home shows the outcome. Never kills the game; never quits the app (overlay off quits only
 * once Cobblify loaded, and an outcome means it did not).
 */
function endLaunchWithOutcome(outcome) {
  if (sessionReset.isActive()) return;
  launchOutcome = { outcome, kind: lastLaunchKind };
  stopProgressPolling();
  sessionReset.resetToHome("launch_outcome");
  if (outcome === "wrong_version") watchLunarVersion();
}

const VERSION_POLL_MS = 1000;
/** Ready this many looks in a row before the launch goes on (a moment for Lunar to settle). */
const VERSION_READY_LOOKS = 3;
/** The wait gives up after this long; Launch still works by hand. */
const VERSION_WAIT_MS = 10 * 60 * 1000;
let versionTimer = null;
/**
 * While "Set Lunar to 1.8.9" shows, the launch goes on by itself once the player has switched Lunar
 * to 1.8.9 (lunar_version_ready): only after the selection was seen on another version and then
 * reads ready for VERSION_READY_LOOKS looks in a row. A Lunar game seen at any point ends the wait
 * for good - the player is playing from Lunar, and no second game is started - and so does
 * VERSION_WAIT_MS. Each call starts a fresh wait.
 */
function watchLunarVersion() {
  clearInterval(versionTimer);
  const started = Date.now();
  let sawOther = false;
  let readyLooks = 0;
  let inFlight = false;
  const timer = setInterval(async () => {
    const waiting = () => versionTimer === timer && launchOutcome?.outcome === "wrong_version" && launchOutcome.kind === "lunar";
    const stop = () => {
      clearInterval(timer);
      if (versionTimer === timer) versionTimer = null;
    };
    if (!waiting() || Date.now() - started > VERSION_WAIT_MS) return stop();
    if (inFlight || setupRunning() || stage.dataset.view === "dash") return;
    const st = launchController.getState();
    if (st.launchBusy || st.activeOutcome != null) return;
    inFlight = true;
    let seen = "unknown";
    try {
      seen = await lunarVersionReady(invoke);
    } catch {
      // fail-soft: the next look asks again
    } finally {
      inFlight = false;
    }
    if (!waiting()) return stop();
    if (seen === "game") return stop();
    if (seen === "other") sawOther = true;
    readyLooks = seen === "ready" && sawOther ? readyLooks + 1 : 0;
    if (readyLooks < VERSION_READY_LOOKS) return;
    stop();
    startLaunch("lunar");
  }, VERSION_POLL_MS);
  versionTimer = timer;
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

/**
 * Cobblify reported in from this launch's game (TEST-NOTES 2, the user 2026-10-11): home gives way to the
 * dashboard, which asks the player to join Hypixel until they are on it. Quitting Minecraft
 * still returns home (the lobby poll's session end). Overlay off never shows it: Cobblify quits instead.
 */
function enterGame() {
  if (gameUp || firstLiveSeen || noDashboard || sessionReset.isActive()) return;
  if (launchController.getState().activeOutcome == null) return;
  gameUp = true;
  homeUntilLive = false;
  // Proof Cobblify loaded (an acknowledged snapshot can beat the progress poll's `loaded`): the overlay-off quit,
  // before the progress poll that would also send it stops.
  overlayExit.onConfirmationSignal("loaded");
  // Any later unavailable poll must not bring back the pre-game manual screen.
  manualRouteSubtitle = null;
  stopProgressPolling();
  suppressRefresh = false;
  connectionShell.show("join");
  stage.dataset.view = "dash";
  refreshUi();
}

function enterDashboard() {
  connectionShell.enterConnected({ firstLive: !firstLiveSeen });
  firstLiveSeen = true;
  gameUp = true;
  // The live snapshot the home view was held for: the marker's job is done.
  homeUntilLive = false;
  // First acknowledged live snapshot: proof Cobblify loaded, for the
  // overlay-off quit (covers dashboard entry stopping progress polling).
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
    enterGame,
  });
}

function applyLobbySnapshot(d, { reconnect = false } = {}) {
  const step = nextLiveDashboard(d, {
    firstLiveSeen,
    wasDisconnected: reconnect,
    eligibleViewOf: boardView,
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
      // The view is a pure model (board-model.js); the board draws it and lays it out for this window and context
      // (board-render.js, board-layout.js), never re-deciding the layout on a data tick.
      boardUi.draw(step.view);
      lastKey = key;
      dash.dataset.ctx = ctx.toLowerCase();
      if (d.teams && d.teams.length) dash.dataset.teams = String(d.teams.length);
      else delete dash.dataset.teams;
    } catch (e) {
      // The dashboard view is already up. A render that throws used to leave that view on screen with an empty,
      // never-shown roster - a black window with no way back. Say so instead, and keep the next snapshot free to
      // redraw.
      console.error(e);
      boardUi.fail(`The roster could not be drawn. ${String(e)}`);
      delete dash.dataset.ctx;
      delete dash.dataset.teams;
      lastKey = null;
    }
  }
  dash.classList.add("on");
}

/** Forget the drawn roster: the board, its header title and the data hooks. */
function clearBoard() {
  dash.classList.remove("on");
  boardUi.clear();
  delete dash.dataset.ctx;
  delete dash.dataset.teams;
  lastKey = null;
}

// A resized window, a new display scale or another font is a new layout problem: the same view is laid out again.
let relayoutFrame = 0;
function relayoutBoard() {
  if (relayoutFrame) return;
  relayoutFrame = (window.requestAnimationFrame ?? ((f) => setTimeout(f, 16)))(() => {
    relayoutFrame = 0;
    if (stage.dataset.view !== "dash") return;
    try {
      boardUi.relayout();
    } catch (e) {
      console.error(e);
    }
  });
}
new ResizeObserver(relayoutBoard).observe(stage);
document.addEventListener("cobblify:units", relayoutBoard);
document.addEventListener("cobblify:theme", () => {
  boardUi.invalidate();
  relayoutBoard();
});
// A face that finishes loading (the bold Minecraft face is first used by the board) changes every width.
document.fonts?.addEventListener?.("loadingdone", () => {
  boardUi.invalidate();
  relayoutBoard();
});
// The connection screens follow the board's scale: placed whenever the shell shows, changes mode or busy state.
new window.MutationObserver(relayoutBoard).observe(joining, {
  attributes: true,
  attributeFilter: ["class", "data-conn", "aria-busy"],
});
// The header's view name and the board's a11y state follow the board's visibility.
new window.MutationObserver(() => {
  const on = dash.classList.contains("on");
  dash.setAttribute("aria-hidden", on ? "false" : "true");
  el("dash-title").hidden = !on;
}).observe(dash, { attributes: true, attributeFilter: ["class"] });

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
    ctx === "session_ended_resetting" ||
    ctx === "session_changed_resetting" ||
    ctx === "launch_aborted_resetting"
  ) {
    stage.dataset.view = "dash";
    connectionShell.enterTerminal(ctx);
  } else if (ctx === "join") {
    stage.dataset.view = "dash";
    connectionShell.show("join");
  } else if (ctx === "manual") {
    stage.dataset.view = "dash";
    connectionShell.show("manual", { subtitle: manualRouteSubtitleFor("launcher_opened") });
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
