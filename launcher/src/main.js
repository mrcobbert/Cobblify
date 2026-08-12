import "@fontsource/unbounded/latin-700.css";
import "@fontsource/dm-mono/latin-400.css";
import "@fontsource/dm-mono/latin-500.css";
import "./style.css";

import { createHeroScene } from "./scene.js";

const el = (id) => document.getElementById(id);
const stage = el("stage");
const joining = el("joining");
const dash = el("dash");
const scene = createHeroScene(el("hero-canvas"));

/**
 * The Tauri bridge is injected by the app shell (withGlobalTauri). Outside it -
 * `npm run preview` in a browser - fall back to a stub so the whole flow can be
 * watched without touching the real Lunar config or the mod's lobby.json.
 *   ?state=ready|lunar|jars|error  picks the initial status case
 *   ?ctx=lobby|queue|game          picks which dashboard the preview renders
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

const PREVIEW_PARTY = [
  pp({ name: "you_", rank: "[MVP+]", fkdr: 5.9, wlr: 3.8, finalKills: 15200, kd: 3.2 }),
  pp({ name: "duo_diff", rank: "[VIP+]", fkdr: 7.2, wlr: 4.9, finalKills: 19000, kd: 3.9 }),
];

const PREVIEW_LOBBY = {
  lobby: {
    v: 1,
    seq: 1001,
    context: "LOBBY",
    inHypixel: true,
    self: "you_",
    partyCount: null,
    yourParty: PREVIEW_PARTY,
    teams: [],
    players: [
      pp({ name: "xXShadowXx", rank: "[MVP++]", fkdr: 22.7, wlr: 14.0, finalKills: 71200, kd: 8.9, seraphThreat: 4, seraphTags: ["REACH"], urchinTags: ["BLATANT"] }),
      pp({ name: "Tenko", rank: "[MVP++]", fkdr: 14.2, wlr: 9.1, finalKills: 48210, kd: 6.7, seraphThreat: 2 }),
      pp({ name: "ok_zenith", rank: "[MVP+]", fkdr: 8.4, wlr: 5.2, finalKills: 22140, kd: 4.1 }),
      pp({ name: "Prot_IV_Dia", rank: "[MVP+]", fkdr: 6.8, wlr: 4.4, finalKills: 17700, kd: 3.6 }),
      pp({ name: "Frostbyte_", rank: "[MVP]", fkdr: 4.6, wlr: 3.1, finalKills: 9800, kd: 2.8 }),
      pp({ name: "mossling", rank: "[MVP]", fkdr: 3.3, wlr: 2.0, finalKills: 5600, kd: 2.2 }),
      pp({ name: "aqua_gg", rank: "[VIP+]", fkdr: 2.1, wlr: 1.4, finalKills: 3120, kd: 1.6 }),
      pp({ name: "Bread_Enjoyer", rank: "[VIP]", fkdr: 1.05, wlr: 0.9, finalKills: 880, kd: 1.1 }),
      pp({ name: "coolkid2013", rank: "", fkdr: 0.42, wlr: 0.5, finalKills: 120, kd: 0.7 }),
      pp({ name: "iToxicWaffle", rank: "", state: "NICKED", nicked: true }),
      pp({ name: "Grandpa_Joe", rank: "", state: "NEVER_PLAYED" }),
      pp({ name: "lagswitch99", rank: "[VIP]", state: "LOADING" }),
    ],
  },
  queue: {
    v: 1,
    seq: 1002,
    context: "QUEUE",
    inHypixel: true,
    self: "you_",
    partyCount: 5,
    yourParty: PREVIEW_PARTY,
    teams: [],
    players: [
      pp({ name: "wallhax_", rank: "[VIP]", fkdr: 19.1, wlr: 11.4, finalKills: 60300, kd: 7.7, urchinTags: ["GHOST"] }),
      pp({ name: "Tenko", rank: "[MVP++]", fkdr: 14.2, wlr: 9.1, finalKills: 48210, kd: 6.7, seraphThreat: 2 }),
      pp({ name: "ok_zenith", rank: "[MVP+]", fkdr: 8.4, wlr: 5.2, finalKills: 22140, kd: 4.1 }),
      pp({ name: "GodBridgeGod", nicked: true, realName: "Frostbyte_", rank: "[MVP]", fkdr: 4.6, wlr: 3.1, finalKills: 9800, kd: 2.8 }),
      pp({ name: "sniped_u", rank: "", state: "LOADING" }),
    ],
  },
  game: {
    v: 1,
    seq: 1003,
    context: "GAME",
    inHypixel: true,
    self: "you_",
    partyCount: null,
    yourParty: PREVIEW_PARTY,
    players: [],
    teams: [
      {
        name: "Red",
        players: [
          pp({ name: "xXShadowXx", rank: "[MVP++]", fkdr: 22.7, wlr: 14.0, finalKills: 71200, kd: 8.9, seraphThreat: 4, seraphTags: ["REACH"], urchinTags: ["BLATANT"] }),
          pp({ name: "Tenko", rank: "[MVP++]", fkdr: 14.2, wlr: 9.1, finalKills: 48210, kd: 6.7, seraphThreat: 2 }),
          pp({ name: "ok_zenith", rank: "[MVP+]", fkdr: 8.4, wlr: 5.2, finalKills: 22140, kd: 4.1 }),
        ],
      },
      {
        name: "Blue",
        players: [
          pp({ name: "you_", rank: "[MVP+]", fkdr: 5.9, wlr: 3.8, finalKills: 15200, kd: 3.2 }),
          pp({ name: "duo_diff", rank: "[VIP+]", fkdr: 7.2, wlr: 4.9, finalKills: 19000, kd: 3.9 }),
          pp({ name: "Bread_Enjoyer", rank: "[VIP]", fkdr: 1.05, wlr: 0.9, finalKills: 880, kd: 1.1 }),
        ],
      },
      {
        name: "Green",
        players: [
          pp({ name: "mossling", rank: "[MVP]", fkdr: 3.3, wlr: 2.0, finalKills: 5600, kd: 2.2 }),
          pp({ name: "coolkid2013", rank: "", fkdr: 0.42, wlr: 0.5, finalKills: 120, kd: 0.7 }),
          pp({ name: "Grandpa_Joe", rank: "", state: "NEVER_PLAYED" }),
        ],
      },
      {
        name: "Yellow",
        players: [
          pp({ name: "aqua_gg", rank: "[VIP+]", fkdr: 2.1, wlr: 1.4, finalKills: 3120, kd: 1.6 }),
          pp({ name: "iNicked", rank: "", state: "NICKED", nicked: true }),
          pp({ name: "Prot_IV_Dia", rank: "[MVP+]", state: "LOADING" }),
        ],
      },
    ],
  },
};

let previewLaunchAt = null;

function previewInvoke(command) {
  if (command === "status") {
    const cases = {
      ready: { state: "ready", message: "Cobblify v0.8.0 ready - Right Shift for settings in game", mod_version: "0.8.0", conflicts: [] },
      lunar: { state: "blocked", message: "Quit Lunar Client so Cobblify can finish setup, then reopen this app.", mod_version: "0.8.0", conflicts: [] },
      jars: {
        state: "blocked",
        message: "Another Cobblify jar is in ~/.weave/mods/. Remove the one you do not want, then reopen Cobblify.",
        mod_version: "0.8.0",
        conflicts: [
          "/Users/you/.weave/mods/Cobblify-Lunar-0.7.2.jar",
          "/Users/you/.weave/mods/Cobblify-Lunar-dev.jar",
        ],
      },
      error: { state: "error", message: "resources/manifest.json is missing.", mod_version: null, conflicts: [] },
    };
    const which = new URLSearchParams(location.search).get("state");
    return new Promise((resolve) => setTimeout(() => resolve(cases[which] ?? cases.ready), 250));
  }

  if (command === "launch_lunar") {
    previewLaunchAt = Date.now();
    return Promise.resolve();
  }

  if (command === "lobby_state") {
    const t = previewLaunchAt ? Date.now() - previewLaunchAt : 0;
    // Hold on MENU for a moment after launch so the interstitial shows.
    if (t < 4000) return Promise.resolve({ context: "MENU", inHypixel: false });
    const which = new URLSearchParams(location.search).get("ctx") || "lobby";
    return Promise.resolve(PREVIEW_LOBBY[which] ?? PREVIEW_LOBBY.lobby);
  }

  return Promise.resolve();
}

// ── initial status render (loading / ready / blocked / error) ───────────────
function render(status) {
  const state = ["ready", "blocked", "error"].includes(status.state)
    ? status.state
    : "error";
  const version = status.mod_version ? `v${status.mod_version}` : null;
  const conflicts = status.conflicts ?? [];

  if (state === "ready") {
    el("chip-text").textContent = version ?? "installed";
  } else if (state === "blocked") {
    el("chip-text").textContent = version ?? "—";

    const jars = conflicts.length > 0;
    el("blocked-title").textContent = jars ? "Conflicting jars" : "Waiting on Lunar";
    el("blocked-message").textContent = status.message;
    el("blocked-hint").textContent = jars
      ? "Cobblify never deletes a file it did not write."
      : "Lunar rewrites its own settings when it quits, so Cobblify waits rather than fighting it.";

    const list = el("blocked-paths");
    list.replaceChildren(
      ...conflicts.map((path) => {
        const li = document.createElement("li");
        li.textContent = path;
        return li;
      }),
    );
    list.hidden = !jars;
  } else {
    el("chip-text").textContent = version ?? "—";
    el("error-message").textContent =
      status.message || "Cobblify could not read its own setup.";
  }

  stage.dataset.state = state;
  scene?.setMood(state);
}

// ── launch → swap the homepage out for the dashboard ────────────────────────
// Fires Lunar's official play deep link: the game boots on the active 1.8.9
// profile and auto-joins Hypixel. The agent lives in Lunar's own config, so
// Cobblify loads either way. Once fired, the homepage leaves and the dashboard
// takes over, watching ~/.cobblify/lobby.json for the live roster.
const launch = el("launch");
launch.addEventListener("click", async () => {
  const label = el("launch-label");
  const error = el("launch-error");
  error.hidden = true;
  launch.disabled = true;
  launch.classList.add("is-loading");
  label.textContent = "Heading to Hypixel";
  try {
    await invoke("launch_lunar");
  } catch (e) {
    error.textContent = String(e);
    error.hidden = false;
    launch.disabled = false;
    launch.classList.remove("is-loading");
    label.textContent = "Launch Lunar";
    return;
  }
  label.textContent = "See you in game";
  enterDashboard();
});

// Swap views: the CSS on data-view="dash" hides the homepage copy, drops the
// voxel to its corner, and reveals the dashboard overlay below the header band.
function enterDashboard() {
  stage.dataset.view = "dash";
  joining.classList.add("on");
  startLobbyPolling();
}

// ── lobby polling ───────────────────────────────────────────────────────────
const LOBBY_POLL_MS = 700;
let lobbyTimer = null;
let lastKey = null;

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
  const ctx = d.context;
  const live = d.inHypixel === true && (ctx === "LOBBY" || ctx === "QUEUE" || ctx === "GAME");

  if (!live) {
    dash.classList.remove("on");
    joining.classList.add("on");
    lastKey = null;
    return;
  }

  joining.classList.remove("on");
  const key = `${ctx}:${d.seq ?? ""}`;
  if (key !== lastKey) {
    try {
      dash.innerHTML = renderDashboard(d);
      lastKey = key;
    } catch {
      return; // a malformed roster must not blank the dashboard
    }
  }
  dash.classList.add("on");
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
  const rank = p.rank ? `<span class="rank">${esc(p.rank)}</span>` : "";
  const flags = chips && chips.length ? `<span class="flags-inline">${chips.join("")}</span>` : "";
  return `<div class="pname"><span class="sweat-bar"></span>
    <div class="identity">${rank}<span class="pn">${esc(p.name)}</span></div>${flags}</div>`;
}

function row(p) {
  if (p.state === "LOADING") {
    return `<div class="prow loading">
      ${pname(p, ['<span class="chip state">resolving</span>'])}
      <div class="stat fkdr"><span class="skel" style="width:28px"></span></div>
      <div class="stat cell-wlr"><span class="skel" style="width:24px"></span></div>
      <div class="stat cell-finals"><span class="skel" style="width:34px"></span></div>
      <div class="stat cell-kd"><span class="skel" style="width:22px"></span></div></div>`;
  }

  if (p.state === "NEVER_PLAYED" || p.state === "ERROR") {
    const lbl = p.state === "ERROR" ? "error" : "never played";
    return `<div class="prow">
      ${pname(p, [`<span class="chip state">${lbl}</span>`])}
      <div class="stat muted fkdr">—</div><div class="stat muted cell-wlr">—</div>
      <div class="stat muted cell-finals">—</div><div class="stat muted cell-kd">—</div></div>`;
  }

  if (p.state === "NICKED" && !p.realName) {
    return `<div class="prow">
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

  return `<div class="prow t${t} ${cheat ? "cheater" : ""}">
    ${pname(p, chips)}
    <div class="stat fkdr">${n2(p.fkdr)}</div>
    <div class="stat cell-wlr">${n2(p.wlr)}</div>
    <div class="stat cell-finals">${nInt(p.finalKills)}</div>
    <div class="stat cell-kd">${n2(p.kd)}</div></div>`;
}

const colHead = `<div class="col-head"><span>Player</span><span>FKDR</span><span>WLR</span>
  <span>Finals</span><span>K/D</span></div>`;

function roster(players) {
  return `<div class="roster">${colHead}${players.map(row).join("")}</div>`;
}

function partySection(list) {
  if (!list || !list.length) return "";
  return `<div class="sect"><h3>Your Party</h3><div class="rule"></div><span class="n">${list.length}</span></div>
    ${roster(list)}`;
}

function teamAvg(t) {
  const ok = (t.players ?? []).filter((p) => p.state === "OK");
  return ok.length ? ok.reduce((s, p) => s + (p.fkdr ?? 0), 0) / ok.length : 0;
}

function renderDashboard(d) {
  const ctx = d.context;
  if (ctx === "QUEUE") return renderQueue(d);
  if (ctx === "GAME") return renderGame(d);
  return renderLobby(d);
}

function renderLobby(d) {
  const players = bySweat(d.players);
  return `
    <div class="ctx-head"><span class="live"></span>
      <span class="ctx-title">Main Lobby</span>
      <span class="ctx-sub">· ${players.length} players</span><div class="spacer"></div>
      <span class="ctx-note">sorted by FKDR</span></div>
    ${partySection(d.yourParty)}
    <div class="sect"><h3>Lobby</h3><div class="rule"></div><span class="n">${players.length}</span></div>
    ${roster(players)}`;
}

function renderQueue(d) {
  const players = bySweat(d.players);
  const parties = typeof d.partyCount === "number" ? `${d.partyCount} parties · ` : "";
  return `
    <div class="ctx-head"><span class="live"></span>
      <span class="ctx-title">Queue</span>
      <span class="ctx-sub">· ${parties}${players.length} known</span><div class="spacer"></div>
      <span class="ctx-note">names hidden until they type</span></div>
    ${partySection(d.yourParty)}
    <div class="sect"><h3>Chatted</h3><div class="rule"></div><span class="n">only typed players resolve</span></div>
    ${roster(players)}
    <div class="empty-note">Remaining players stay hidden by Hypixel until the match starts.</div>`;
}

function renderGame(d) {
  const teams = (d.teams ?? [])
    .map((t) => ({ t, agg: teamAvg(t) }))
    .sort((a, b) => b.agg - a.agg);
  const maxAgg = teams.length ? teams[0].agg : 0;
  const teamsHtml = teams
    .map(({ t, agg }) => {
      const target = teams.length > 0 && agg === maxAgg;
      return `<div class="team-head ${target ? "targeted" : ""}"><h3>${esc(t.name)}</h3>
        <span class="agg">avg FKDR ${agg.toFixed(1)}</span><div class="rule"></div>
        ${target ? '<span class="target-badge">Target</span>' : ""}</div>
        ${roster(bySweat(t.players))}`;
    })
    .join("");
  return `
    <div class="ctx-head"><span class="live"></span>
      <span class="ctx-title">Live Match</span>
      <span class="ctx-sub">· ${teams.length} teams</span><div class="spacer"></div>
      <span class="ctx-note">your party first, then sweatiest team</span></div>
    ${partySection(d.yourParty)}${teamsHtml}`;
}

// ── boot ────────────────────────────────────────────────────────────────────
invoke("status")
  .then(render)
  .catch((e) =>
    render({ state: "error", message: String(e), mod_version: null, conflicts: [] }),
  );
