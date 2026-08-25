/** Interstitial copy for all connection shell modes. */

/** @typedef {'manual' | 'joining' | 'waiting' | 'connected' | 'disconnected' | 'preexisting_game' | 'session_ended' | 'session_changed' | 'launch_aborted' | 'session_ended_resetting' | 'session_changed_resetting' | 'launch_aborted_resetting'} ConnectionMode */

/** @type {Record<ConnectionMode, { title: string, subtitle: string, loading: boolean }>} */
export const CONNECTION_COPY = {
  manual: {
    title: "Ready",
    subtitle: "Launch to open Lunar or Prism.",
    loading: false,
  },
  joining: {
    title: "Joining Hypixel",
    subtitle: "Connecting to network",
    loading: true,
  },
  waiting: {
    title: "Still Waiting for Hypixel",
    subtitle: "Join Hypixel manually in Minecraft, or close and relaunch Cobblify.",
    loading: false,
  },
  connected: {
    title: "Connected",
    subtitle: "",
    loading: false,
  },
  disconnected: {
    title: "Disconnected from Hypixel",
    subtitle: "Reconnect in Minecraft to resume.",
    loading: false,
  },
  preexisting_game: {
    title: "Game Already Running",
    subtitle: "Close the game, then try again.",
    loading: false,
  },
  // Final failure copy: shown only after the automatic reset genuinely
  // gave up, when quit-and-reopen is truthful again.
  session_ended: {
    title: "Game Session Ended",
    subtitle: "Quit Cobblify, then open it again to continue.",
    loading: false,
  },
  session_changed: {
    title: "Game Session Changed",
    subtitle: "Quit Cobblify, then open it again to continue.",
    loading: false,
  },
  // The user cancelled the launch themselves; the same final failure rule
  // applies if the reset genuinely gave up.
  launch_aborted: {
    title: "Launch Cancelled",
    subtitle: "Quit Cobblify, then open it again to continue.",
    loading: false,
  },
  // Transitional copy while the reset retries.
  session_ended_resetting: {
    title: "Game Ended",
    subtitle: "Returning home…",
    loading: true,
  },
  session_changed_resetting: {
    title: "Game Session Changed",
    subtitle: "Returning home…",
    loading: true,
  },
  launch_aborted_resetting: {
    title: "Launch Cancelled",
    subtitle: "Returning home…",
    loading: true,
  },
};

/**
 * @param {ConnectionMode} mode
 */
export function connectionCopy(mode) {
  return CONNECTION_COPY[mode] ?? CONNECTION_COPY.joining;
}

const FORBIDDEN_PRE_LIVE = /Hypixel|joining|connected/i;

/**
 * Route-specific manual subtitle after auto-join-off launch dispatch.
 * `launch_unconfirmed` copy is deliberately CONDITIONAL: the launch may be
 * in flight, so the user is never unconditionally told to press Play.
 * @param {'launcher_opened' | 'game_launch_requested' | 'launch_unconfirmed'} action
 * @param {'lunar' | 'forge'} [kind]
 */
export function manualRouteSubtitleFor(action, kind = "forge") {
  if (action === "launcher_opened") {
    return "Lunar is open — press Play in Lunar when ready.";
  }
  if (action === "launch_unconfirmed") {
    return "Lunar is launching — if nothing happens, press Play in Lunar.";
  }
  if (kind === "lunar") {
    return "Lunar is launching — use Minecraft when ready.";
  }
  return "Prism is launching — use Minecraft when ready.";
}

export { FORBIDDEN_PRE_LIVE };
