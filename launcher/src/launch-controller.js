import {
  launchForge,
  launchLunar,
  launchPreferences,
  lobbyState,
  acknowledgeLobbySnapshot,
  launchProgress,
  setAutoJoinHypixel,
  setUseExternalOverlay,
} from "./tauri-contract.js";
import { classifyLive } from "./lobby-validator.js";

const STAGE_LABEL_ON = {
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

const STAGE_LABEL_OFF = {
  fired: "Preparing Lunar…",
  attached: "Weave attached",
  discovered: "Mods found",
  mixing: "Loading Cobblify",
  forge_fired: "Preparing Prism...",
  forge_attached: "Forge started",
  forge_discovered: "Mods found",
  forge_mixing: "Loading Cobblify",
  forge_settled: "Prism Started",
  settled: "In game…",
};

const LUNAR_OPEN_LABELS = {
  idle: "Launch Lunar",
  loading: "Launching Lunar…",
  done: "Lunar Opened",
};

const PRISM_LABELS = {
  idle: "Launch Prism",
  loading: "Launching Prism…",
  done: "Prism Started",
};

/**
 * @typedef {{ kind: 'lunar' | 'forge', label: string, loading: boolean, enabled: boolean }} LaunchButtonView
 */

/**
 * @param {Function} invoke
 * @param {{ onChange?: () => void }} [opts]
 */
export function createLaunchController(invoke, { onChange } = {}) {
  let operationGen = 0;
  let refreshGen = 0;
  let prefOpGen = 0;
  let pollGen = 0;
  let launchGen = 0;
  let optimisticAutoJoin = true;
  let confirmedAutoJoin = true;
  let optimisticOverlay = true;
  let confirmedOverlay = true;
  let prefHealth = "missing";
  let prefDiagnostic = null;
  let prefUncertain = false;
  /** @type {'autoJoin' | 'overlay' | null} Which key the uncertainty belongs to. */
  let prefUncertainKey = null;
  let prefError = null;
  let prefSaving = false;
  let launchBusy = false;
  let activeOutcome = null;
  /** @type {'idle' | 'loading' | 'done'} */
  let launchPhase = "idle";
  /** @type {LaunchButtonView[]} */
  let launchButtons = [];

  function notify() {
    onChange?.();
  }

  function syncFromView(view) {
    optimisticAutoJoin = view.autoJoinHypixel;
    confirmedAutoJoin = view.autoJoinHypixel;
    optimisticOverlay = view.useExternalOverlay;
    confirmedOverlay = view.useExternalOverlay;
    prefHealth = view.health;
    prefDiagnostic = view.diagnostic ?? null;
    prefUncertain = false;
    prefUncertainKey = null;
    prefError = null;
  }

  async function refreshPreferences() {
    const gen = ++refreshGen;
    try {
      const view = await launchPreferences(invoke);
      if (gen !== refreshGen) return;
      syncFromView(view);
      notify();
    } catch (e) {
      if (gen !== refreshGen) return;
      prefError = String(e);
      notify();
    }
  }

  /**
   * Shared two-key save path. One save gate covers both checkboxes; repairs
   * are keyed - an uncertain save can only be repaired through the same key
   * and value. Saves are rejected outright during a launch or an active
   * session, matching the DOM disable so a programmatic or stale change
   * event cannot mutate a preference mid-launch.
   *
   * @param {'autoJoin' | 'overlay'} key
   * @param {boolean} enabled
   * @param {{ repair?: boolean }} [opts]
   */
  async function setPreference(key, enabled, { repair = false } = {}) {
    if (prefSaving || launchBusy || activeOutcome != null) return;
    const optimistic = key === "autoJoin" ? optimisticAutoJoin : optimisticOverlay;
    if (prefUncertain && !repair && (key !== prefUncertainKey || enabled !== optimistic)) {
      prefError = "Repair the settings before changing them.";
      notify();
      return;
    }
    if (prefUncertain && repair && key !== prefUncertainKey) {
      prefError = "Repair the settings before changing them.";
      notify();
      return;
    }
    const gen = ++prefOpGen;
    refreshGen += 1;
    const prevAutoJoin = optimisticAutoJoin;
    const prevOverlay = optimisticOverlay;
    prefSaving = true;
    if (key === "autoJoin") {
      optimisticAutoJoin = enabled;
    } else {
      optimisticOverlay = enabled;
    }
    prefError = null;
    notify();
    try {
      const reply =
        key === "autoJoin"
          ? await setAutoJoinHypixel(invoke, enabled)
          : await setUseExternalOverlay(invoke, enabled);
      if (gen !== prefOpGen) return;
      if (reply.status === "saved" || reply.status === "reconciled") {
        // Replies carry BOTH confirmed booleans: resync the whole document.
        confirmedAutoJoin = reply.autoJoinHypixel;
        optimisticAutoJoin = reply.autoJoinHypixel;
        confirmedOverlay = reply.useExternalOverlay;
        optimisticOverlay = reply.useExternalOverlay;
        prefUncertain = false;
        prefUncertainKey = null;
        prefHealth = "valid";
        prefDiagnostic = null;
      } else if (reply.status === "not_saved") {
        optimisticAutoJoin = prevAutoJoin;
        optimisticOverlay = prevOverlay;
        prefError = reply.diagnostic;
      } else if (reply.status === "indeterminate") {
        prefUncertain = true;
        prefUncertainKey = key;
        prefError = "Could not confirm the save. Try again.";
      }
      notify();
    } catch (e) {
      if (gen !== prefOpGen) return;
      optimisticAutoJoin = prevAutoJoin;
      optimisticOverlay = prevOverlay;
      prefError = String(e);
      notify();
    } finally {
      if (gen === prefOpGen) {
        prefSaving = false;
        notify();
      }
    }
  }

  async function setAutoJoin(enabled, opts) {
    return setPreference("autoJoin", enabled, opts);
  }

  async function setOverlay(enabled, opts) {
    return setPreference("overlay", enabled, opts);
  }

  function lunarLabel(autoJoin, phase) {
    if (!autoJoin) {
      if (phase === "loading") return LUNAR_OPEN_LABELS.loading;
      if (phase === "done") {
        // Only the never-fired open-only route settles as "Lunar Opened";
        // a direct launch that settled reads as in-game.
        return activeOutcome?.action === "launcher_opened"
          ? LUNAR_OPEN_LABELS.done
          : "In game…";
      }
      return LUNAR_OPEN_LABELS.idle;
    }
    if (phase === "loading") return "Heading to Hypixel";
    // Auto-join ON now settles on the home button too (the dashboard waits
    // for a live snapshot), reusing the settled stage copy.
    if (phase === "done") return STAGE_LABEL_ON.settled;
    return "Launch Lunar";
  }

  function forgeLabel(autoJoin, phase) {
    if (!autoJoin) {
      if (phase === "loading") return PRISM_LABELS.loading;
      if (phase === "done") return PRISM_LABELS.done;
      return PRISM_LABELS.idle;
    }
    if (phase === "loading") return "Heading to Hypixel";
    if (phase === "done") return STAGE_LABEL_ON.forge_settled;
    return "Launch Prism";
  }

  /**
   * @param {{ lunarReady: boolean, forgeReady: boolean }} ready
   */
  function projectLaunchButtons(ready, phase = "idle") {
    const active = activeOutcome != null;
    const buttons = [];
    if (ready.lunarReady) {
      buttons.push({
        kind: "lunar",
        label: lunarLabel(optimisticAutoJoin, phase),
        loading: phase === "loading",
        enabled: !launchBusy && !prefSaving && !prefUncertain && !active,
      });
    }
    if (ready.forgeReady) {
      buttons.push({
        kind: "forge",
        label: forgeLabel(optimisticAutoJoin, phase),
        loading: phase === "loading",
        enabled: !launchBusy && !prefSaving && !prefUncertain && !active,
      });
    }
    launchButtons = buttons;
  }

  /**
   * @param {'lunar' | 'forge'} kind
   * @param {{ onLaunchReply: (reply: any) => void, onProgressStart: () => void }} hooks
   */
  async function launch(kind, hooks) {
    if (launchBusy || prefSaving || prefUncertain || activeOutcome != null) return;
    if (prefHealth === "invalid") {
      prefError = "Repair the auto-join setting before launching.";
      notify();
      return;
    }
    launchBusy = true;
    launchPhase = "loading";
    refreshGen += 1;
    const gen = ++operationGen;
    notify();
    try {
      const reply =
        kind === "lunar"
          ? await launchLunar(invoke, confirmedAutoJoin, confirmedOverlay)
          : await launchForge(invoke, confirmedAutoJoin, confirmedOverlay);
      if (gen !== operationGen) return;
      launchBusy = false;
      if (reply.status === "launched") {
        activeOutcome = reply.outcome;
        launchGen = reply.generation;
        pollGen = reply.generation;
        if (!reply.outcome.autoJoinHypixel) {
          launchPhase =
            reply.outcome.action === "launcher_opened" ? "done" : "loading";
        } else {
          launchPhase = "loading";
        }
        hooks.onLaunchReply(reply);
        if (
          reply.outcome.autoJoinHypixel ||
          reply.outcome.action === "game_launch_requested" ||
          reply.outcome.action === "launch_unconfirmed"
        ) {
          hooks.onProgressStart(reply.outcome.autoJoinHypixel);
        }
      } else if (reply.status === "preexisting_game") {
        launchPhase = "idle";
        hooks.onLaunchReply(reply);
      } else if (reply.status === "rejected") {
        launchPhase = "idle";
        if (reply.code === "stale_preference" && reply.preferences) {
          optimisticAutoJoin = reply.preferences.autoJoinHypixel;
          confirmedAutoJoin = reply.preferences.autoJoinHypixel;
          optimisticOverlay = reply.preferences.useExternalOverlay;
          confirmedOverlay = reply.preferences.useExternalOverlay;
        }
        // The backend's cooldown message carries the remaining seconds; the
        // fallback stays truthful without inventing a number.
        prefError =
          reply.message ??
          (reply.code === "launch_cooldown"
            ? "The previous launch may still be starting - try again in a moment."
            : reply.code);
        notify();
      }
      notify();
    } catch (e) {
      if (gen !== operationGen) return;
      launchBusy = false;
      launchPhase = "idle";
      prefError = String(e);
      notify();
    }
  }

  async function tryAgainLaunch(kind, hooks) {
    return launch(kind, hooks);
  }

  /**
   * @param {{ onPoll: (poll: any) => void }} hooks
   */
  async function pollLobbyOnce(hooks) {
    const gen = pollGen;
    try {
      const poll = await lobbyState(invoke);
      if (gen !== pollGen) return;
      await hooks.onPoll(poll);
    } catch {
      // fail-soft
    }
  }

  async function acknowledgeSnapshot(token, generation, snapshot) {
    return acknowledgeLobbySnapshot(invoke, token, generation, snapshot);
  }

  /**
   * @param {{ lunarReady: boolean, forgeReady: boolean }} ready
   */
  function projectForCurrentPhase(ready) {
    projectLaunchButtons(ready, launchPhase);
    return launchButtons;
  }

  return {
    refreshPreferences,
    setAutoJoin,
    setOverlay,
    launch,
    tryAgainLaunch,
    pollLobbyOnce,
    acknowledgeSnapshot,
    pollProgress: () => launchProgress(invoke),
    getState() {
      return {
        optimisticAutoJoin,
        confirmedAutoJoin,
        optimisticOverlay,
        confirmedOverlay,
        prefHealth,
        prefDiagnostic,
        prefUncertain,
        prefUncertainKey,
        prefError,
        prefSaving,
        launchBusy,
        activeOutcome,
        launchButtons,
        launchGen,
        pollGen,
        launchPhase,
        showAutoJoinSwitch: launchButtons.length > 0,
        stageLabels: (autoJoin = true) => (autoJoin ? STAGE_LABEL_ON : STAGE_LABEL_OFF),
        classifyLive,
      };
    },
    projectLaunchButtons,
    projectForCurrentPhase,
    settleLaunchPhase() {
      launchPhase = "done";
      notify();
    },
    bumpPollGen() {
      pollGen = launchGen;
    },
    /**
     * Session reset step 1: discard every in-flight async result before any
     * UI changes. Generations 0 can never match a backend generation, and
     * bumping the op counters orphans pending launch/save/refresh replies.
     */
    invalidateSession() {
      operationGen += 1;
      prefOpGen += 1;
      refreshGen += 1;
      pollGen = 0;
      launchGen = 0;
      launchBusy = false;
      prefSaving = false;
    },
    /**
     * Session reset commit: back to a launchable idle home. `view`, when
     * present (carried by both reset success variants), resyncs both
     * checkboxes.
     */
    resetLaunchSession(view) {
      activeOutcome = null;
      launchPhase = "idle";
      prefError = null;
      if (view) syncFromView(view);
      notify();
    },
  };
}
