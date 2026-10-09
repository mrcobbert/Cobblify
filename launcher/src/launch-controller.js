import {
  launchForge,
  launchLunar,
  launchPreferences,
  lobbyState,
  acknowledgeLobbySnapshot,
  launchProgress,
  setUseExternalOverlay,
} from "./tauri-contract.js";
import { classifyLive } from "./lobby-validator.js";

/**
 * Launches never join a server (0.18): Lunar opens through the bare play link, Prism with
 * `--launch <id>` only, and the player joins Hypixel themselves. No label here mentions
 * Hypixel before the game is live.
 */
const STAGE_LABELS = {
  fired: "Preparing Lunar…",
  attached: "Weave attached",
  discovered: "Mods found",
  mixing: "Loading Cobblify",
  forge_fired: "Preparing Prism...",
  forge_downloading: "Downloading Minecraft",
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
  let optimisticOverlay = true;
  let confirmedOverlay = true;
  let prefHealth = "missing";
  let prefDiagnostic = null;
  let prefUncertain = false;
  /** @type {'overlay' | null} Which key the uncertainty belongs to (the only one 0.18 saves). */
  let prefUncertainKey = null;
  let prefError = null;
  let prefSaving = false;
  let launchBusy = false;
  let activeOutcome = null;
  /** @type {'idle' | 'loading' | 'done'} */
  let launchPhase = "idle";
  /**
   * @type {'lunar' | 'forge' | null} The target that owns the current launch
   * session, from the click until the session resets to idle. Only its button
   * is projected while set: the other target leaves the row instead of
   * mirroring a phase it never entered.
   */
  let launchKind = null;
  /** @type {LaunchButtonView[]} */
  let launchButtons = [];

  function notify() {
    onChange?.();
  }

  function syncFromView(view) {
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
   * The External Overlay save path (0.18's only launch preference). An
   * uncertain save can only be repaired with the same value. Saves are
   * rejected outright during a launch or an active session, matching the DOM
   * disable so a programmatic or stale change event cannot mutate a
   * preference mid-launch.
   *
   * @param {'overlay'} key
   * @param {boolean} enabled
   * @param {{ repair?: boolean }} [opts]
   */
  async function setPreference(key, enabled, { repair = false } = {}) {
    if (prefSaving || launchBusy || activeOutcome != null) return;
    if (prefUncertain && !repair && (key !== prefUncertainKey || enabled !== optimisticOverlay)) {
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
    const prevOverlay = optimisticOverlay;
    prefSaving = true;
    optimisticOverlay = enabled;
    prefError = null;
    notify();
    try {
      const reply = await setUseExternalOverlay(invoke, enabled);
      if (gen !== prefOpGen) return;
      if (reply.status === "saved" || reply.status === "reconciled") {
        confirmedOverlay = reply.useExternalOverlay;
        optimisticOverlay = reply.useExternalOverlay;
        prefUncertain = false;
        prefUncertainKey = null;
        prefHealth = "valid";
        prefDiagnostic = null;
      } else if (reply.status === "not_saved") {
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

  async function setOverlay(enabled, opts) {
    return setPreference("overlay", enabled, opts);
  }

  function lunarLabel(phase) {
    if (phase === "loading") return LUNAR_OPEN_LABELS.loading;
    if (phase === "done") {
      // Only the never-fired open-only route settles as "Lunar Opened";
      // a direct launch that settled reads as in-game.
      return activeOutcome?.action === "launcher_opened"
        ? LUNAR_OPEN_LABELS.done
        : STAGE_LABELS.settled;
    }
    return LUNAR_OPEN_LABELS.idle;
  }

  function forgeLabel(phase) {
    if (phase === "loading") return PRISM_LABELS.loading;
    if (phase === "done") return PRISM_LABELS.done;
    return PRISM_LABELS.idle;
  }

  /**
   * @param {{ lunarReady: boolean, forgeReady: boolean }} ready
   */
  function projectLaunchButtons(ready, phase = "idle") {
    const active = activeOutcome != null;
    const shows = (kind) => launchKind == null || launchKind === kind;
    const buttons = [];
    if (ready.lunarReady && shows("lunar")) {
      buttons.push({
        kind: "lunar",
        label: lunarLabel(phase),
        loading: phase === "loading",
        enabled: !launchBusy && !prefSaving && !prefUncertain && !active,
      });
    }
    if (ready.forgeReady && shows("forge")) {
      buttons.push({
        kind: "forge",
        label: forgeLabel(phase),
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
      prefError = "Repair Cobblify's settings before launching.";
      notify();
      return;
    }
    launchBusy = true;
    launchPhase = "loading";
    launchKind = kind;
    refreshGen += 1;
    const gen = ++operationGen;
    notify();
    try {
      const reply =
        kind === "lunar"
          ? await launchLunar(invoke, confirmedOverlay)
          : await launchForge(invoke, confirmedOverlay);
      if (gen !== operationGen) return;
      launchBusy = false;
      if (reply.status === "launched") {
        activeOutcome = reply.outcome;
        launchGen = reply.generation;
        pollGen = reply.generation;
        launchPhase = reply.outcome.action === "launcher_opened" ? "done" : "loading";
        hooks.onLaunchReply(reply);
        if (
          reply.outcome.action === "game_launch_requested" ||
          reply.outcome.action === "launch_unconfirmed"
        ) {
          hooks.onProgressStart();
        }
      } else if (reply.status === "preexisting_game") {
        launchPhase = "idle";
        launchKind = null;
        hooks.onLaunchReply(reply);
      } else if (reply.status === "rejected" && reply.code === "wrong_version") {
        // Lunar is surely on another version: nothing was opened, the home asks for 1.8.9.
        launchPhase = "idle";
        launchKind = null;
        hooks.onWrongVersion?.();
      } else if (reply.status === "rejected") {
        launchPhase = "idle";
        launchKind = null;
        if (reply.code === "stale_preference" && reply.preferences) {
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
      launchKind = null;
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
    setOverlay,
    launch,
    tryAgainLaunch,
    pollLobbyOnce,
    acknowledgeSnapshot,
    pollProgress: () => launchProgress(invoke),
    getState() {
      return {
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
        launchKind,
        stageLabels: () => STAGE_LABELS,
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
     * present (carried by both reset success variants), resyncs the
     * External Overlay checkbox.
     */
    resetLaunchSession(view) {
      activeOutcome = null;
      launchPhase = "idle";
      launchKind = null;
      prefError = null;
      if (view) syncFromView(view);
      notify();
    },
  };
}
