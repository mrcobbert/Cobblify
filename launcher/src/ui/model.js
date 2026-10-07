/**
 * What the 0.18 home, its client menu and the settings sheet show, as a pure function of the existing backend's
 * replies (stage 1 of the redesign: the new look on the existing backend).
 *
 * First run is interim until stage 2 (Choose Your Setup): with no client ready, the key sits alone in the middle
 * and is the existing setup action for the first issue (setup-view.js), the next issue's action under it.
 */
import { ISSUE, readyTargets, setupBlocks, targetOf, topLevelError } from "../setup-view.js";
import { CL, uiId } from "./art.js";

/** Backend progress stages (progress.rs) -> the prototype's launch phases, in order. */
const STAGE_INDEX = {
  fired: 0, attached: 1, discovered: 2, mixing: 3, settled: 4,
  forge_fired: 0, forge_attached: 1, forge_discovered: 2, forge_mixing: 3, forge_settled: 4,
};
const PHASE_PCT = [10, 35, 60, 85];

/**
 * The launch key while launching. The game being up reads "Join Hypixel" (a wait): launches
 * never join a server, the player does. A Cobblify-made Prism instance's first launch shows
 * "Downloading Minecraft" (22%) between "Opening Prism" and "Starting Minecraft".
 */
export function launchHero(id, stage) {
  if (stage === "forge_downloading") return { kind: "prog", label: "Downloading Minecraft", pct: 22 };
  const i = STAGE_INDEX[stage] ?? 0;
  if (i >= 4) return { kind: "wait", label: "Join Hypixel" };
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

/** The remembered client if it is still ready, else the first ready one. */
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

/** How the menu's "Add" row (and Settings' Add) reaches a client that isn't set up, or null if it can't. */
export function addFor(status, id) {
  const t = targetOf(status, CL[id].kind);
  if (!t || t.state === "ready") return null;
  if (t.issue === ISSUE.MISSING_LAUNCHER) return { id, act: "get", installed: false };
  if (t.action === "choose" && (t.candidates ?? []).length) return { id, act: "add", installed: true };
  const block = setupBlocks(status).find((b) => b.kind === CL[id].kind);
  const action = block?.actions?.[0];
  return action ? { id, act: "setup", action, installed: true } : null;
}

function interim(status, picked) {
  if (status.state === "loading") return { hero: { kind: "wait", label: "Checking" }, keys: [], said: "Checking setup" };
  const err = topLevelError(status);
  if (err) {
    return {
      hero: { kind: "act", label: "Try again", act: "setup", action: err.actions[0] },
      keys: [],
      said: `${err.heading}. ${err.detail}`,
    };
  }
  const blocks = setupBlocks(status);
  if (!blocks.length) return { hero: { kind: "wait", label: "Checking" }, keys: [], said: "" };
  const [first, ...rest] = blocks;
  const keys = rest.flatMap((b) => (b.actions[0] ? [{ label: b.actions[0].label, act: "setup", action: b.actions[0] }] : []));
  const said = blocks.map((b) => b.heading).join(". ");
  if (first.candidates.length) {
    return { ...chooseView(first, picked), keys, said };
  }
  const [a, ...more] = first.actions;
  const hero = a ? { kind: "act", label: a.label, act: "setup", action: a } : { kind: "wait", label: first.heading };
  return { hero, keys: [...more.map((m) => ({ label: m.label, act: "setup", action: m })), ...keys], said };
}

function chooseView(block, picked) {
  const candidates = block.candidates.map((c) => ({ id: c.id, name: c.name }));
  const pick = candidates.some((c) => c.id === picked) ? picked : candidates[0].id;
  return {
    hero: { kind: "act", label: "Set up Prism", act: "setup", action: { kind: "setup_prism", candidateId: pick } },
    lead: { kind: "choose", candidates, picked: pick },
  };
}

/**
 * @param {{
 *   status: object,
 *   selected?: string|null,
 *   picked?: string|null,
 *   adding?: string|null,
 *   launch?: { kind?: string|null, phase?: string, busy?: boolean, stage?: string|null,
 *              outcome?: "wrong_version"|"not_loaded"|null, outcomeKind?: "lunar"|"forge"|null,
 *              prefError?: string|null, prefHealth?: string, prefUncertain?: boolean, showCancel?: boolean },
 *   update?: object,
 * }} input
 */
export function homeModel({ status, selected = null, picked = null, adding = null, launch = {}, update = {} }) {
  const ready = readyTargets(status).map((t) => uiId(t.kind));
  const order = ["lunar", "prism"].filter((id) => ready.includes(id));
  const launching = !!launch.kind && (launch.busy || launch.phase === "loading" || launch.phase === "done");
  const updReady = update.state === "ready";

  if (!order.length) {
    const v = interim(status, picked);
    return { solo: true, clients: [], sel: null, add: null, hero: v.hero, keys: v.keys, lead: v.lead ?? null, under: null, said: v.said, launching: false, updReady };
  }

  const sel = launching ? uiId(launch.kind) : pickSelected(order, selected);
  const other = ["lunar", "prism"].find((id) => !order.includes(id));
  const add = other ? addFor(status, other) : null;
  const base = { solo: false, clients: order, sel, add, lead: null, under: null, keys: [], launching, updReady };

  if (adding && add && add.id === adding && add.act === "add") {
    const block = setupBlocks(status).find((b) => b.kind === CL[adding].kind && b.candidates.length);
    if (block) {
      const v = chooseView(block, picked);
      return { ...base, sel: adding, adding, hero: v.hero, lead: v.lead, keys: [{ label: "Cancel", act: "cancel-add" }], said: "Choose a Prism instance" };
    }
  }
  if (launching) {
    const hero = launchHero(sel, launch.stage);
    return { ...base, hero, keys: launch.showCancel ? [{ label: "Cancel", act: "cancel" }] : [], said: hero.label };
  }
  if (blocksLaunch(update)) {
    const hero = updateHero(update);
    return { ...base, hero, said: `${update.availableVersion ?? "An update"} is required to play` };
  }
  if (launch.prefHealth === "invalid" || launch.prefUncertain) {
    return { ...base, hero: { kind: "act", label: "Repair", act: "repair-pref" }, said: launch.prefError || "Cobblify's settings file needs repair" };
  }
  if (launch.prefError) {
    return { ...base, hero: { kind: "act", label: "Try again", act: "play" }, said: launch.prefError };
  }
  // The outcome belongs to the client that launched; picking the other one leaves it behind.
  const ended = !launch.outcomeKind || CL[sel].kind === launch.outcomeKind ? outcomeView(launch.outcome) : null;
  if (ended) return { ...base, ...ended };
  return { ...base, hero: { kind: "play" }, said: `Launch Cobblify in ${CL[sel].name}` };
}

/** The settings sheet's rows. */
export function sheetModel({ status, launch = {}, update = {}, overlayOn = true, overlaySaving = false, version = "", devPending = null }) {
  const locked = !!launch.active || !!launch.busy;
  const lunarReady = readyTargets(status).some((t) => t.kind === "lunar");
  const forgeReady = readyTargets(status).some((t) => t.kind === "forge");
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
  return {
    locked,
    reason: locked ? "Locked while launching" : null,
    overlay: { on: !!overlayOn, disabled: locked || overlaySaving || !!launch.prefUncertain },
    autoUpdate: { on: !!u.autoUpdateEnabled, disabled: false },
    // While the channel save is in flight the snapshot still carries the OLD channel: the switch keeps the
    // player's click (devPending) instead of visibly undoing it.
    dev: { on: u.channelSaving && devPending != null ? devPending : u.updateChannel === "dev", disabled: locked || !!u.channelSaving || u.state === "checking" },
    version: { label, word, key },
    lunarRepair: lunarReady,
    prismAdd: forgeReady ? null : addFor(status, "prism"),
  };
}
