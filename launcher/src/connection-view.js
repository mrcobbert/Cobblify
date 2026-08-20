/** Interstitial copy for all connection shell modes. */

/** @typedef {'manual' | 'joining' | 'waiting' | 'connected' | 'disconnected' | 'preexisting_game' | 'session_ended' | 'session_changed'} ConnectionMode */

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
 * @param {'launcher_opened' | 'game_launch_requested'} action
 */
export function manualRouteSubtitleFor(action) {
  if (action === "launcher_opened") {
    return "Lunar is open — press Play in Lunar when ready.";
  }
  return "Prism is launching — use Minecraft when ready.";
}

export { FORBIDDEN_PRE_LIVE };
