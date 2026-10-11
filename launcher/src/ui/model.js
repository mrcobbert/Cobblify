/**
 * What the 0.18 home, its client menu and the settings sheet show, as a pure function of the backend's replies and
 * the few things the window remembers (spec-flows.md §1: first run, setup, the Prism pick, add from home, launch).
 *
 * - Nothing set up: Choose Your Setup, alone in the middle (no island). Its menu sets a client up, or opens the
 *   download page of a missing one and waits for the install ("Install Lunar" + Download).
 * - A setup run (`setup://progress` snapshots): the slim bar with its under-row, the stops, Try again; the island is
 *   built one part per check.
 * - The Prism pick: the instance list over "Set up Prism"; alone on a first run, the island greyed from home.
 * - Home: Launch for the set-up clients, the launch phases and outcomes; while an added client is being installed,
 *   Choose Your Setup over its install row, Launch paused.
 */
import { targetOf } from "../setup-view.js";
import { CL, uiId } from "./art.js";

/** Backend progress stages (progress.rs) -> the prototype's launch phases, in order. */
const STAGE_INDEX = {
  fired: 0, attached: 1, discovered: 2, mixing: 3, settled: 4,
  forge_fired: 0, forge_attached: 1, forge_discovered: 2, forge_mixing: 3, forge_settled: 4,
};
const PHASE_PCT = [10, 35, 60, 85];

/** The six setup checks, read out to screen readers (proto CHECKS / PRISM_CHECKS). */
export const CHECKS = ["Checking Cobblify's own files", "Looking for Lunar and Prism", "Checking Lunar has been signed in", "Checking Lunar is closed", "Looking for old Cobblify files", "Adding Cobblify to Lunar"];
export const PRISM_CHECKS = ["Checking Cobblify's own files", "Looking for Lunar and Prism", "Checking Prism has been signed in", "Checking the instance is closed", "Looking for old Cobblify files", "Adding Cobblify to the instance"];
const N = CHECKS.length;

/** The setup labels a long check shows instead of "Setting up …" (snapshot `label`). */
const LONG = { making_instance: "Making a 1.8.9 instance", java: "Downloading Java 8", vanilla: "Downloading Minecraft 1.8.9" };

/**
 * The launch key while launching. Home holds it until Cobblify reports in (`loaded`), then the window is the
 * dashboard, whose Join Hypixel message waits for the player (TEST-NOTES 2): a quiet log (`settled`) is no proof the
 * game is up, so it keeps "Adding Cobblify". A Cobblify-made Prism instance's first launch shows
 * "Downloading Minecraft" (22%) between "Opening Prism" and "Starting Minecraft".
 */
export function launchHero(id, stage) {
  if (stage === "forge_downloading") return { kind: "prog", label: "Downloading Minecraft", pct: 22 };
  const i = Math.min(3, STAGE_INDEX[stage] ?? 0);
  const labels = [id === "prism" ? "Opening Prism" : "Opening Lunar", "Starting Minecraft", "Loading mods", "Adding Cobblify"];
  return { kind: "prog", label: labels[i], pct: PHASE_PCT[i] };
}

/**
 * How the last launch ended, for the home it returned to (`launch.outcome` from the progress
 * poll). `under` is the under-row's data; the view draws it.
 * - wrong_version: Lunar isn't on 1.8.9. Launch stays (it retries); under: "Set Lunar to
 *   1.8.9" + Open.
 * - not_loaded: Minecraft started without Cobblify. "Try again"; under: "Cobblify didn't load".
 */
function outcomeView(outcome) {
  if (outcome === "wrong_version") {
    return { hero: { kind: "play" }, under: { kind: "setLunar" }, said: "Lunar isn't set to 1.8.9." };
  }
  if (outcome === "not_loaded") {
    return {
      hero: { kind: "act", label: "Try again", act: "play" },
      under: { kind: "notLoaded" },
      said: "Cobblify didn't load. Minecraft started without Cobblify.",
    };
  }
  return null;
}

/** The remembered client if it is still set up, else the first set-up one. */
export function pickSelected(readyIds, stored) {
  return readyIds.includes(stored) ? stored : (readyIds[0] ?? null);
}

/** A critical update blocks Launch until it is installing (updater.rs blocks_launch). */
export const blocksLaunch = (u) => u?.critical === true && u?.state !== "installing";

const pct = (u) => (u?.sizeBytes > 0 ? Math.min(100, Math.round((u.downloadedBytes / u.sizeBytes) * 100)) : 0);

function updateHero(u) {
  const v = u.availableVersion || "";
  if (u.state === "downloading" || u.state === "installing") {
    return { kind: "prog", label: `Updating to ${v}`.trim(), pct: u.state === "installing" ? 100 : pct(u) };
  }
  if (u.state === "paused") return { kind: "act", label: "Resume update", act: "update", action: "resume" };
  if (u.state === "ready") return { kind: "act", label: "Restart to update", act: "update", action: "install" };
  if (u.state === "checking") return { kind: "wait", label: "Checking" };
  return { kind: "act", label: `Update to ${v}`.trim(), act: "update", action: "download" };
}

/* ---------- what the status says about a client ---------- */

const targetFor = (status, id) => targetOf(status, CL[id].kind);
/** Cobblify is set up in this client. A ready target always is (older replies carry no `set_up`). */
export const isSetUp = (status, id) => {
  const t = targetFor(status, id);
  return !!t && (t.set_up === true || t.state === "ready");
};
/** The client's app is on this computer. */
export const isInstalled = (status, id) => {
  const t = targetFor(status, id);
  if (!t) return false;
  return typeof t.installed === "boolean" ? t.installed : t.issue !== "missing_launcher";
};
/** Prism's compatible instances for the pick, most recently played first (the backend sorts them). */
export const prismInstances = (status) => targetFor(status, "prism")?.instances ?? [];
/** The clients Cobblify is set up in, Lunar first. */
export const setUpClients = (status) => ["lunar", "prism"].filter((id) => isSetUp(status, id));

/** How the menu's "Add" row (and Settings' Add) reaches the client that isn't set up. */
export function addFor(status, id) {
  return { id, installed: isInstalled(status, id) };
}

/* ---------- setup ---------- */

/** The setup check names a client's screen reader hears. */
export const checkName = (id, i) => (id === "prism" ? PRISM_CHECKS : CHECKS)[Math.max(0, Math.min(N - 1, i))];

/**
 * Where the grey ghost part hangs (the check whose part waits), carried across snapshots: a failure and the game-open
 * stop hang the part of the check they stopped at; it keeps hanging through that check's re-run (Try again, or the
 * game closing then "Closing Lunar") and drops once the check passes.
 */
export function stuckAfter(prev, snap) {
  if (!snap) return null;
  if (snap.phase === "failed" || snap.stop === "game_open") return snap.check;
  if (prev != null && (snap.check > prev || snap.phase === "done")) return null;
  return prev;
}

/** The slim bar's fill: 4, 17, 33, 50, 67, 83, then 100 once done. */
export const setupPct = (check) => Math.max(4, Math.round((check / N) * 100));

function setupView(snap, stuckAt) {
  const id = uiId(snap.kind), name = CL[id].short;
  const check = Math.max(0, Math.min(N, snap.check | 0));
  const built = check / N;
  if (snap.phase === "done") {
    return { hero: { kind: "slim", label: `Setting up ${name}`, pct: 100 }, island: { built: 1 }, said: checkName(id, N - 1) + "." };
  }
  if (snap.phase === "failed") {
    return {
      hero: { kind: "act", label: "Try again", act: "retry-setup" },
      island: { built, stuck: true },
      said: "Setup didn't finish. Play waits until setup finishes.",
    };
  }
  const stuck = stuckAt != null && stuckAt === check;
  if (snap.stop === "signin") {
    return {
      hero: { kind: "slim", label: `Sign in to ${name}`, pct: setupPct(check), open: { label: "Open", id } },
      island: { built },
      said: id === "lunar" ? "Sign in to Lunar. Lunar hasn't been signed in on this computer yet." : "Sign in to Prism. Prism has no Microsoft account on this computer yet.",
    };
  }
  if (snap.stop === "game_open") {
    return {
      hero: { kind: "wait", label: "Close Minecraft" },
      island: { built, stuck: true },
      said: id === "lunar" ? "Game open. Minecraft is running from Lunar. Setup finishes once it closes." : "Game open. Minecraft is running. Setup finishes once it closes.",
    };
  }
  const closing = snap.stop === "closing_lunar" || snap.label === "closing_lunar";
  const long = closing ? null : LONG[snap.label] ?? null;
  return {
    hero: { kind: "slim", label: closing ? `Closing ${name}` : long ?? `Setting up ${name}`, pct: setupPct(check) },
    island: { built, stuck },
    said: closing ? `Closing ${name}.` : (long ?? checkName(id, check)) + ".",
  };
}

/* ---------- first run ---------- */

function chooseSaid(status, wait) {
  if (wait) return `Waiting for ${CL[wait].name} to be installed. Setup starts by itself once it is.`;
  const inst = ["lunar", "prism"].filter((id) => isInstalled(status, id));
  if (inst.length === 1) return `Choose a client: ${CL[inst[0]].name} is on this computer, the other one downloads first.`;
  if (inst.length) return "Choose a client: Lunar Client and Prism Launcher are both on this computer.";
  return "Choose a client: Cobblify runs inside Lunar Client or Prism Launcher, and neither is on this computer yet.";
}

/* ---------- the model ---------- */

/**
 * @param {{
 *   status: object,
 *   selected?: string|null,
 *   launch?: { kind?: string|null, phase?: string, busy?: boolean, stage?: string|null,
 *              outcome?: "wrong_version"|"not_loaded"|null, outcomeKind?: "lunar"|"forge"|null,
 *              prefError?: string|null, prefHealth?: string, prefUncertain?: boolean, showCancel?: boolean,
 *              inGame?: boolean },
 *   update?: object,
 *   setup?: { snap: object, stuckAt?: number|null } | null,   the setup run the window shows
 *   wait?: string|null,      first run: the client whose install is awaited
 *   addWait?: string|null,   home: the client "Add" is waiting to be installed (Launch is paused meanwhile)
 *   pick?: { picked: string|null } | null,   the Prism instance pick is up
 *   freshSetup?: boolean,    setup has just finished (the ready line)
 * }} input
 *
 * hero kinds: play | setup (Choose Your Setup) | slim (setup's bar) | prog | wait | act.
 * island: null = the whole island; else { built, stuck, grey }. solo = no island, the column centred.
 */
export function homeModel({ status, selected = null, launch = {}, update = {}, setup = null, wait = null, addWait = null, pick = null, freshSetup = false }) {
  const order = setUpClients(status);
  const launching = !!launch.kind && (launch.busy || launch.phase === "loading" || launch.phase === "done");
  const updReady = update.state === "ready";
  const blank = { clients: order, add: null, lead: null, under: null, keys: [], launching: false, updReady, island: null, setupMenu: null, setup: false, pick: false };

  if (status.state === "loading") {
    return { ...blank, solo: true, clients: [], sel: null, hero: { kind: "wait", label: "Checking" }, said: "Checking setup" };
  }
  if (status.state === "error" && !(status.targets ?? []).length) {
    return { ...blank, solo: true, clients: [], sel: null, hero: { kind: "act", label: "Try again", act: "refresh" }, said: `Setup didn't finish. ${status.message ?? ""}`.trim() };
  }

  if (setup?.snap) {
    const id = uiId(setup.snap.kind);
    const v = setupView(setup.snap, setup.stuckAt ?? null);
    const clients = ["lunar", "prism"].filter((c) => c === id || order.includes(c));
    return { ...blank, solo: false, clients, sel: id, setup: true, ...v };
  }

  if (pick) {
    const list = prismInstances(status);
    const picked = list.some((i) => i.id === pick.picked) ? pick.picked : (list[0]?.id ?? null);
    const solo = !order.length;
    const n = list.length;
    return {
      ...blank,
      solo,
      clients: ["lunar", "prism"].filter((c) => c === "prism" || order.includes(c)),
      sel: "prism",
      pick: true,
      hero: { kind: "act", label: "Set up Prism", act: "setup-prism" },
      lead: { kind: "choose", candidates: list.map((i) => ({ id: i.id, name: i.name, detail: i.detail ?? "" })), picked },
      island: solo ? null : { built: 1, stuck: true, grey: true },
      said: `Choose an instance. Prism has ${n} ${n === 1 ? "instance" : "instances"} that can run Cobblify.`,
    };
  }

  if (!order.length) {
    return {
      ...blank,
      solo: true,
      clients: [],
      sel: null,
      hero: { kind: "setup" },
      under: wait ? { kind: "install", id: wait } : null,
      setupMenu: ["lunar", "prism"].map((id) => ({ id, installed: isInstalled(status, id) })),
      said: chooseSaid(status, wait),
    };
  }

  const sel = launching ? uiId(launch.kind) : pickSelected(order, selected);
  const other = ["lunar", "prism"].find((id) => !order.includes(id));
  const add = other ? addFor(status, other) : null;
  const base = { ...blank, solo: false, clients: order, sel, add, launching };

  if (launching) {
    const hero = launchHero(sel, launch.stage);
    return { ...base, hero, keys: launch.showCancel ? [{ label: "Cancel", act: "cancel" }] : [], said: hero.label };
  }
  if (blocksLaunch(update)) {
    const hero = updateHero(update);
    return { ...base, hero, said: `${update.availableVersion ?? "An update"} is required to play` };
  }
  // Add's wait (TEST-NOTES 1, the user 2026-10-11): Launch pauses while Cobblify waits for the added client to be
  // installed. The key is Choose Your Setup over "Install <client>" + Download, as on a first run; its menu lists the
  // set-up clients (picking one returns to Launch for it) and the missing one (its download page again).
  if (addWait && !order.includes(addWait)) {
    return {
      ...base,
      hero: { kind: "setup" },
      under: { kind: "install", id: addWait },
      setupMenu: [...order, addWait].map((id) => ({ id, installed: isInstalled(status, id), ready: order.includes(id) })),
      said: chooseSaid(status, addWait),
    };
  }
  if (launch.prefHealth === "invalid" || launch.prefUncertain) {
    return { ...base, hero: { kind: "act", label: "Repair", act: "repair-pref" }, said: launch.prefError || "Cobblify's settings file needs repair" };
  }
  if (launch.prefError) {
    return { ...base, hero: { kind: "act", label: "Try again", act: "play" }, said: launch.prefError };
  }
  // Cobblify's files in Lunar are out of date (an update) while Lunar is open, so they can't be written yet. Nothing
  // failed: Repair runs setup, whose "Closing Lunar" quits Lunar politely, writes them and goes on.
  const issue = targetFor(status, sel)?.issue;
  if (issue === "running_lunar" || issue === "not_current") {
    return { ...base, hero: { kind: "act", label: "Repair", act: "repair-setup" }, said: "Cobblify's files in Lunar need updating." };
  }
  // A set-up client whose files could not be brought up to date (a game was open, a jar it may not touch, a
  // write that failed): its setup runs again, through the flow and its stops.
  if (targetFor(status, sel)?.state !== "ready") {
    return { ...base, hero: { kind: "act", label: "Try again", act: "repair-setup" }, said: "Setup didn't finish. Play waits until setup finishes." };
  }
  // The outcome belongs to the client that launched; picking the other one leaves it behind.
  const ended = !launch.outcomeKind || CL[sel].kind === launch.outcomeKind ? outcomeView(launch.outcome) : null;
  if (ended) return { ...base, ...ended };
  const hero = { kind: "play" };
  const said = freshSetup ? (order.length === 1 ? "In game, Right Shift opens Cobblify's settings." : "") : `Launch Cobblify in ${CL[sel].name}`;
  return { ...base, hero, said };
}

/**
 * The settings sheet's rows. The client rows (Lunar Repair, Prism Add / Change) are disabled while launching, in
 * game, and while a setup is up (defaults 15: no interleaving).
 */
export function sheetModel({ status, launch = {}, update = {}, overlayOn = true, overlaySaving = false, version = "", devPending = null, setupActive = false }) {
  const locked = !!launch.active || !!launch.busy;
  const rowsOff = locked || !!setupActive;
  const u = update;
  let word = "Up to date", key = { label: "Check", act: "update", action: "check" }, label = `Cobblify ${version || u.currentVersion || ""}`.trim();
  switch (u.state) {
    case "checking": word = "Checking"; key = { ...key, disabled: true }; break;
    case "error": word = "Check failed"; break;
    case "available": word = u.availableVersion ?? "Available"; key = { label: "Update", act: "update", action: "download" }; break;
    case "critical_required": word = `${u.availableVersion ?? ""} required`.trim(); key = { label: "Update", act: "update", action: "download" }; break;
    case "downloading": word = `${pct(u)}%`; key = null; break;
    case "paused": word = "Paused"; key = { label: "Resume", act: "update", action: "resume" }; break;
    case "ready": label = `Cobblify ${u.availableVersion ?? ""}`.trim(); word = "ready"; key = { label: "Restart", act: "update", action: "install" }; break;
    case "installing": word = "Installing"; key = null; break;
    default: break;
  }
  const loaded = status.state !== "loading" && !(status.state === "error" && !(status.targets ?? []).length);
  const prismSet = isSetUp(status, "prism");
  return {
    locked,
    reason: locked ? (launch.inGame ? "Locked during the game" : "Locked while launching") : null,
    overlay: { on: !!overlayOn, disabled: locked || overlaySaving || !!launch.prefUncertain },
    autoUpdate: { on: !!u.autoUpdateEnabled, disabled: false },
    // While the channel save is in flight the snapshot still carries the OLD channel: the switch keeps the
    // player's click (devPending) instead of visibly undoing it.
    dev: { on: u.channelSaving && devPending != null ? devPending : u.updateChannel === "dev", disabled: locked || !!u.channelSaving || u.state === "checking" },
    version: { label, word, key },
    lunar: loaded && isSetUp(status, "lunar") ? { label: "Repair", act: "repair-lunar", disabled: rowsOff } : null,
    prism: !loaded ? null
      : prismSet ? { label: "Change", act: "sheet-change", disabled: rowsOff }
      : { label: "Add", act: "sheet-add", disabled: rowsOff, add: addFor(status, "prism") },
  };
}
