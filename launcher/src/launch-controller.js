import {
  launchForge,
  launchLunar,
  launchPreferences,
  lobbyState,
  acknowledgeLobbySnapshot,
  launchProgress,
  setAutoJoinHypixel,
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
  idle: "Open Lunar",
  loading: "Opening Lunar…",
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
  let prefHealth = "missing";
  let prefDiagnostic = null;
  let prefUncertain = false;
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

  async function refreshPreferences() {
    const gen = ++refreshGen;
    try {
      const view = await launchPreferences(invoke);
      if (gen !== refreshGen) return;
      optimisticAutoJoin = view.autoJoinHypixel;
      confirmedAutoJoin = view.autoJoinHypixel;
      prefHealth = view.health;
      prefDiagnostic = view.diagnostic ?? null;
      prefUncertain = false;
      prefError = null;
      notify();
    } catch (e) {
      if (gen !== refreshGen) return;
      prefError = String(e);
      notify();
    }
  }

  /**
   * @param {boolean} enabled
   * @param {{ repair?: boolean }} [opts]
   */
  async function setAutoJoin(enabled, { repair = false } = {}) {
    if (prefSaving) return;
    if (prefUncertain && !repair && enabled !== optimisticAutoJoin) {
      prefError = "Repair the setting before changing it.";
      notify();
      return;
    }
    const gen = ++prefOpGen;
    refreshGen += 1;
    const prev = optimisticAutoJoin;
    prefSaving = true;
    optimisticAutoJoin = enabled;
    prefError = null;
    notify();
    try {
      const reply = await setAutoJoinHypixel(invoke, enabled);
      if (gen !== prefOpGen) return;
      if (reply.status === "saved" || reply.status === "reconciled") {
        confirmedAutoJoin = reply.autoJoinHypixel;
        optimisticAutoJoin = reply.autoJoinHypixel;
        prefUncertain = false;
        prefHealth = "valid";
        prefDiagnostic = null;
      } else if (reply.status === "not_saved") {
        optimisticAutoJoin = prev;
        prefError = reply.diagnostic;
      } else if (reply.status === "indeterminate") {
        prefUncertain = true;
        prefError = "Could not confirm the save. Try again.";
      }
      notify();
    } catch (e) {
      if (gen !== prefOpGen) return;
      optimisticAutoJoin = prev;
      prefError = String(e);
      notify();
    } finally {
      if (gen === prefOpGen) {
        prefSaving = false;
        notify();
      }
    }
  }

  function lunarLabel(autoJoin, phase) {
    if (!autoJoin) {
      if (phase === "loading") return LUNAR_OPEN_LABELS.loading;
      if (phase === "done") return LUNAR_OPEN_LABELS.done;
      return LUNAR_OPEN_LABELS.idle;
    }
    if (phase === "loading") return "Heading to Hypixel";
    return "Launch Lunar";
  }

  function forgeLabel(autoJoin, phase) {
    if (!autoJoin) {
      if (phase === "loading") return PRISM_LABELS.loading;
      if (phase === "done") return PRISM_LABELS.done;
      return PRISM_LABELS.idle;
    }
    if (phase === "loading") return "Heading to Hypixel";
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
          ? await launchLunar(invoke, confirmedAutoJoin)
          : await launchForge(invoke, confirmedAutoJoin);
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
        if (reply.outcome.autoJoinHypixel || reply.outcome.action === "game_launch_requested") {
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
        }
        prefError = reply.message ?? reply.code;
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
    launch,
    tryAgainLaunch,
    pollLobbyOnce,
    acknowledgeSnapshot,
    pollProgress: () => launchProgress(invoke),
    getState() {
      return {
        optimisticAutoJoin,
        confirmedAutoJoin,
        prefHealth,
        prefDiagnostic,
        prefUncertain,
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
  };
}
