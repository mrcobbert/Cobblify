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
import { createConnectionModel } from "./connection-state.js";
import { connectionCopy } from "./connection-view.js";
import {
  ISSUE,
  launchLabel,
  readyTargets,
  setupBlocks,
  topLevelError,
} from "./setup-view.js";

const el = (id) => document.getElementById(id);
const stage = el("stage");
const joining = el("joining");
const dash = el("dash");
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
 *   ?ctx=lobby|joining|disconnected  lobby dashboard / joining / disconnected
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
      inHypixel: true,
      self: "you_",
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

  if (command === "launch_lunar") {
    previewLaunchAt = Date.now();
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
      return Promise.resolve(PREVIEW_LOBBY[which]);
    }
    const t = previewLaunchAt ? Date.now() - previewLaunchAt : 0;
    // Idle until well after the bar settles, so the homepage-wait shows.
    if (t < 6000) return Promise.resolve({ context: "MENU", inHypixel: false });
    return Promise.resolve(PREVIEW_LOBBY.lobby);
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
  el("launch-label").textContent = launchLabel("lunar");
  el("launch-forge-label").textContent = launchLabel("forge");

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
  if (setupBusy) return;
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

// ── launch: stay on the homepage, narrate the boot, wait for the server ─────
// Fires Lunar's official play deep link: the game boots on the active 1.8.9
// profile and auto-joins Hypixel. The agent lives in Lunar's own config, so
// Cobblify loads either way. The homepage STAYS - the button itself narrates
// the boot off real weave-log milestones - and the dashboard is entered only
// by applyLobby, the moment lobby.json reports we are actually inside Hypixel.
// The backend ignores any lobby.json older than this launch, so a stale roster
// from a prior session can never be what flips the view.
const STAGE_LABEL = {
  fired: "Preparing Lunar…",
  attached: "Weave attached",
  discovered: "Mods found",
  mixing: "Loading Cobblify",
  forge_fired: "Preparing Prism...",
  forge_attached: "Forge started",
  forge_discovered: "Mods found",
  forge_mixing: "Loading Cobblify",
  forge_settled: "In game - joining Hypixel...",
  settled: "In game - joining Hypixel…",
};
const PROGRESS_POLL_MS = 600;
let progressTimer = null;

const launch = el("launch");
const launchForge = el("launch-forge");
launchForge.addEventListener("click", async () => {
  const label = el("launch-forge-label");
  const error = el("launch-error");
  error.hidden = true;
  launchForge.disabled = true;
  launchForge.classList.add("is-loading");
  suppressRefresh = true;
  label.textContent = "Heading to Hypixel";
  try {
    await invoke("launch_forge");
  } catch (e) {
    error.textContent = String(e);
    error.hidden = false;
    launchForge.disabled = false;
    launchForge.classList.remove("is-loading");
    suppressRefresh = false;
    label.textContent = launchLabel("forge");
    return;
  }
  label.textContent = STAGE_LABEL.forge_fired;
  progressTimer = setInterval(async () => {
    try {
      const p = await invoke("launch_progress");
      if (p) label.textContent = STAGE_LABEL[p.stage] ?? STAGE_LABEL.forge_fired;
    } catch {
      // Cosmetic and fail-soft; retry on the next poll.
    }
  }, PROGRESS_POLL_MS);
  beginLaunchSession();
});
launch.addEventListener("click", async () => {
  const label = el("launch-label");
  const error = el("launch-error");
  error.hidden = true;
  launch.disabled = true;
  launch.classList.add("is-loading");
  suppressRefresh = true;
  label.textContent = "Heading to Hypixel";
  try {
    await invoke("launch_lunar");
  } catch (e) {
    error.textContent = String(e);
    error.hidden = false;
    launch.disabled = false;
    launch.classList.remove("is-loading");
    suppressRefresh = false;
    label.textContent = launchLabel("lunar");
    return;
  }
  label.textContent = STAGE_LABEL.fired;
  progressTimer = setInterval(async () => {
    let p;
    try {
      p = await invoke("launch_progress");
    } catch {
      return; // never surface a progress error; try again next tick
    }
    if (!p) return;
    label.textContent = STAGE_LABEL[p.stage] ?? STAGE_LABEL.fired;
  }, PROGRESS_POLL_MS);

  beginLaunchSession();
});

// Swap views: the CSS on data-view="dash" hides the homepage copy, tucks the
// wordmark into the top-left, drops the voxel to its corner, and reveals the
// dashboard overlay below the header band.
// Called exactly once, by applyLobby, on the first FRESH in-Hypixel snapshot.
function enterDashboard() {
  stage.dataset.view = "dash";
  if (progressTimer) {
    clearInterval(progressTimer);
    progressTimer = null;
  }
}

// ── lobby polling ───────────────────────────────────────────────────────────
const LOBBY_POLL_MS = 700;
let lobbyTimer = null;
let lastKey = null;
let lastView = null; // the rendered view, kept so a resize can re-column it
const connection = createConnectionModel();
let interstitialMode = "joining";

function setInterstitialMode(mode, { force = false } = {}) {
  if (!force && interstitialMode === mode) return;
  interstitialMode = mode;
  joining.dataset.conn = mode;
  const copy = connectionCopy(mode);
  el("joining-title").textContent = copy.title;
  el("joining-sub").textContent = copy.subtitle;
  joining.setAttribute("aria-busy", copy.loading ? "true" : "false");
}

function setInterstitialVisible(visible) {
  joining.classList.toggle("on", visible);
  joining.setAttribute("aria-hidden", visible ? "false" : "true");
  const loading = visible && connectionCopy(interstitialMode).loading;
  joining.setAttribute("aria-busy", loading ? "true" : "false");
}

function beginLaunchSession() {
  connection.reset();
  setInterstitialMode("joining");
  startLobbyPolling();
}

function startLobbyPolling() {
  if (lobbyTimer) return;
  pollLobby();
  lobbyTimer = setInterval(pollLobby, LOBBY_POLL_MS);
}

async function pollLobby() {
  let data;
  try {
    data = await invoke("lobby_state");
  } catch {
    return; // fail-soft: swallow a bad poll, retry next tick
  }
  if (!data || typeof data !== "object") return;
  applyLobby(data);
}

function applyLobby(d) {
  const { mode } = connection.tick(d, Date.now());
  const ctx = d.context;
  const inDash = stage.dataset.view === "dash";

  if (mode === "connected") {
    if (!inDash) enterDashboard();
    setInterstitialVisible(false);
    const key = `${ctx}:${d.seq ?? ""}`;
    if (key !== lastKey) {
      try {
        const view = viewOf(d);
        // Hold columns only within a context - a queue must not inherit the
        // lobby's second column just because it was on screen a moment ago.
        const held = dash.dataset.ctx === ctx.toLowerCase() ? currentCols() : 1;
        dash.innerHTML = sheetHtml(view, planCols(view, dash.clientWidth, held));
        lastView = view;
        lastKey = key;
      } catch {
        return; // a malformed roster must not blank the dashboard
      }
      dash.dataset.ctx = ctx.toLowerCase();
      if (d.teams && d.teams.length) dash.dataset.teams = String(d.teams.length);
      else delete dash.dataset.teams;
      fitDash();
    }
    dash.classList.add("on");
    return;
  }

  if (mode === "disconnected") {
    if (!inDash) enterDashboard();
    dash.classList.remove("on");
    dash.replaceChildren();
    delete dash.dataset.ctx;
    delete dash.dataset.density;
    delete dash.dataset.teams;
    lastKey = null;
    lastView = null;
    setInterstitialMode("disconnected");
    setInterstitialVisible(true);
    return;
  }

  // Joining interstitial: initial boot (still on homepage) or a post-live grace
  // gap while Hypixel transfers servers/worlds.
  if (inDash) {
    dash.classList.remove("on");
    setInterstitialMode("joining");
    setInterstitialVisible(true);
    lastKey = null;
  }
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
  const body = cols
    .map((col) => {
      const labels = k > 1 ? `<div class="col-head"><span></span>${STAT_LABELS}</div>` : "";
      return `<div class="col">${labels}${col.map((u) => u.html).join("")}</div>`;
    })
    .join("");
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
  if (!previewing) return;
  mountPreviewBar(bootKind || "lunarReady");
  if (bootParams.get("ctx") === "joining") {
    stage.dataset.view = "dash";
    setInterstitialMode("joining");
    setInterstitialVisible(true);
  } else if (bootParams.get("ctx") === "disconnected") {
    stage.dataset.view = "dash";
    setInterstitialMode("disconnected");
    setInterstitialVisible(true);
  } else if (PREVIEW_LOBBY[bootParams.get("ctx")]) {
    applyLobby(PREVIEW_LOBBY[bootParams.get("ctx")]);
  }
}

if (previewing && bootParams.get("state") === "loading") {
  mountPreviewBar("loading");
} else {
  invoke("refresh_setup")
    .then(afterStatus)
    .catch((e) =>
      afterStatus({
        state: "error",
        message: String(e),
        mod_version: null,
        targets: [],
      }),
    );
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
  bar.innerHTML = `
    <span class="preview-size">900×620</span>
    <span class="preview-group">setup</span>
    <button type="button" data-preview="loading">loading</button>
    ${setupButtons}
    <span class="preview-group">lobby</span>
    <button type="button" data-preview="lobby">lobby</button>
    <button type="button" data-preview="joining">joining</button>
    <button type="button" data-preview="disconnected">disconnected</button>
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
    const btn = e.target.closest("[data-preview]");
    if (btn) showPreview(btn.dataset.preview);
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
  if (progressTimer) {
    clearInterval(progressTimer);
    progressTimer = null;
  }
  if (lobbyTimer) {
    clearInterval(lobbyTimer);
    lobbyTimer = null;
  }
  lastKey = null;
  lastView = null;
  previewLaunchAt = null;
  setupBusy = false;
  suppressRefresh = false;
  connection.reset();
  setInterstitialMode("joining", { force: true });
  setInterstitialVisible(false);
  dash.classList.remove("on");
  dash.replaceChildren();
  delete dash.dataset.ctx;
  delete dash.dataset.density;
  delete dash.dataset.teams;
  stage.dataset.view = "home";
  launch.disabled = false;
  launch.classList.remove("is-loading");
  el("launch-label").textContent = launchLabel("lunar");
  launchForge.disabled = false;
  launchForge.classList.remove("is-loading");
  el("launch-forge-label").textContent = launchLabel("forge");
  el("launch-error").hidden = true;
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
    setInterstitialMode("joining");
    setInterstitialVisible(true);
    params.set("ctx", "joining");
  } else if (kind === "disconnected") {
    render(PREVIEW_STATUS.lunarReady);
    stage.dataset.view = "dash";
    setInterstitialMode("disconnected");
    setInterstitialVisible(true);
    params.set("ctx", "disconnected");
  } else if (PREVIEW_LOBBY[kind]) {
    render(PREVIEW_STATUS.lunarReady);
    applyLobby(PREVIEW_LOBBY[kind]);
    params.set("ctx", kind);
  } else {
    render(resolvePreviewStatus(kind));
    params.set("state", kind);
  }
  history.replaceState(null, "", `${location.pathname}?${params}`);
  markPreviewActive(kind);
}
