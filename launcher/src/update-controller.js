import {
  checkForUpdate,
  deferUpdate,
  installUpdate,
  pauseUpdate,
  resumeUpdate,
  setAutoUpdate as saveAutoUpdate,
  setUpdateChannel as saveUpdateChannel,
  startUpdate,
  updatePreferences,
  updateStatus,
} from "./tauri-contract.js";

const SIX_HOURS_MS = 6 * 60 * 60 * 1000;

/**
 * Frontend coordinator for the updater row. Native code owns downloads,
 * signatures, cache files, critical policy, and install guards; this module
 * owns only UI intent and cadence.
 */
export function createUpdateController(invoke, deps = {}) {
  const schedule = deps.schedule ?? ((fn, ms) => setTimeout(fn, ms));
  const cancelSchedule = deps.cancelSchedule ?? clearTimeout;
  const random = deps.random ?? Math.random;
  const onChange = deps.onChange ?? (() => {});
  let timer = null;
  let generation = 0;
  let busy = false;
  let gameActive = false;
  let state = {
    state: "idle",
    currentVersion: "",
    autoUpdateEnabled: false,
    autoUpdatePrompted: false,
    updateChannel: "stable",
    /** True from the first preference read; the channel box is shown from then on. */
    channelLoaded: false,
    /** True while a channel save is in flight; the box is disabled and clicks are dropped. */
    channelSaving: false,
  };

  const notify = () => onChange({ ...state });
  const merge = (next) => {
    // A message belongs to the state that produced it. A new state that carries none
    // (a later check, a backend status event) must not keep showing an earlier action's
    // reason under its own title.
    const cleared = next && typeof next.state === "string" && !("message" in next) ? { message: null } : {};
    state = { ...state, ...cleared, ...next };
    notify();
  };

  function armCheck() {
    if (timer != null) cancelSchedule(timer);
    const jitter = 0.9 + random() * 0.2;
    timer = schedule(async () => {
      timer = null;
      await check(false);
    }, Math.round(SIX_HOURS_MS * jitter));
  }

  async function bootstrap() {
    const gen = ++generation;
    const [prefs, status] = await Promise.all([
      updatePreferences(invoke),
      updateStatus(invoke),
    ]);
    if (gen !== generation) return;
    merge({ ...status, ...prefs, channelLoaded: true });
    await check(false);
  }

  async function check(manual = true) {
    if (busy) return;
    busy = true;
    const gen = ++generation;
    merge({ state: "checking", manual });
    try {
      const next = await checkForUpdate(invoke, manual);
      if (gen !== generation) return;
      merge({ ...next, manual });
      if (!gameActive && state.autoUpdateEnabled && (next.state === "available" || next.state === "critical_required")) {
        const download = await startUpdate(invoke);
        if (gen === generation && download) merge(download);
      }
    } catch (_) {
      if (gen === generation) merge({ state: "error", diagnosticCode: "check_failed", manual });
    } finally {
      if (gen === generation) busy = false;
      armCheck();
    }
  }

  async function setAutoUpdate(enabled) {
    const reply = await saveAutoUpdate(invoke, enabled);
    if (reply.status !== "saved" && reply.status !== "reconciled") {
      merge({ state: "error", diagnosticCode: "preference_not_saved", manual: true });
      return;
    }
    merge({
      autoUpdateEnabled: reply.autoUpdateEnabled,
      autoUpdatePrompted: reply.autoUpdatePrompted,
      ...(reply.updateChannel ? { updateChannel: reply.updateChannel } : {}),
    });
    if (!gameActive && enabled && (state.state === "available" || state.state === "critical_required")) {
      const next = await startUpdate(invoke);
      if (next) merge(next);
    }
  }

  /**
   * The "Test dev builds" checkbox. Saving the channel is enough for the next
   * scheduled check; a fresh check right away is what makes the row answer
   * the click (a waiting dev build appears, or "Up to date" is confirmed).
   */
  async function setUpdateChannel(channel) {
    // One save at a time: a second click while the first is on disk would race the
    // file lock and could persist the earlier choice last.
    if (state.channelSaving) return;
    merge({ channelSaving: true });
    let reply;
    try {
      reply = await saveUpdateChannel(invoke, channel);
    } catch (_) {
      reply = { status: "not_saved" };
    }
    if (reply.status !== "saved" && reply.status !== "reconciled") {
      merge({ channelSaving: false, state: "error", diagnosticCode: "preference_not_saved", manual: true });
      return;
    }
    merge({
      channelSaving: false,
      autoUpdateEnabled: reply.autoUpdateEnabled,
      autoUpdatePrompted: reply.autoUpdatePrompted,
      updateChannel: reply.updateChannel ?? "stable",
    });
    // A check already in flight (the six-hour timer, say) asked for the old channel.
    // Retire it so its answer is discarded and nothing downloads from it, then ask again.
    generation += 1;
    busy = false;
    await check(true);
  }

  async function action(name) {
    const commands = {
      download: startUpdate,
      pause: pauseUpdate,
      resume: resumeUpdate,
      install: installUpdate,
      defer: deferUpdate,
    };
    try {
      // Enable and Disable go through the preference save, which starts a download of its
      // own; that download can be refused too, and its rejection belongs in this row like
      // any other. Everything the row can dispatch is inside this one guard.
      if (name === "enable") return await setAutoUpdate(true);
      if (name === "disable") return await setAutoUpdate(false);
      if (name === "dismiss_consent") return await setAutoUpdate(false);
      if (name === "check") return await check(true);
      const command = commands[name];
      if (!command) return;
      const next = await command(invoke);
      if (next) merge(next);
    } catch (error) {
      // Native refused the action, or the bridge failed. This row is the only
      // surface the user has for it: an unhandled rejection left the row on
      // its old state, so a refused install looked like nothing happened.
      merge({
        state: "error",
        diagnosticCode: "action_failed",
        message: String(error),
        manual: true,
      });
    }
  }

  function acceptStatus(next) {
    if (next && typeof next.state === "string") merge(next);
  }

  async function setGameActive(active) {
    gameActive = active;
    if (active && state.state === "downloading") {
      await action("pause");
    } else if (!active && state.autoUpdateEnabled && state.state === "paused") {
      await action("resume");
    }
  }

  function dispose() {
    generation += 1;
    if (timer != null) cancelSchedule(timer);
    timer = null;
  }

  return {
    bootstrap,
    check,
    setAutoUpdate,
    setUpdateChannel,
    action,
    acceptStatus,
    setGameActive,
    dispose,
    getState: () => ({ ...state }),
  };
}
