/** @typedef {'manual' | 'connected' | 'disconnected' | 'preexisting_game' | 'session_ended' | 'session_changed'} ConnectionMode */

export const DISCONNECT_GRACE_MS = 1000;

/**
 * Connection state of one launch session. Launches never join a server, so before the first
 * live snapshot the mode is always `manual`: the player joins Hypixel themselves.
 * @param {{ graceMs?: number }} [opts]
 */
export function createConnectionModel({ graceMs = DISCONNECT_GRACE_MS } = {}) {
  let sawLive = false;
  let disconnectConfirmed = false;
  /** @type {number | null} */
  let pendingSince = null;
  /** @type {ConnectionMode} */
  let forcedMode = null;
  let menuAckCount = 0;

  /** @returns {{ mode: ConnectionMode }} */
  function stateAt(now) {
    if (forcedMode) return { mode: forcedMode };
    if (!sawLive) return { mode: "manual" };
    if (pendingSince === null) return { mode: "connected" };
    if (disconnectConfirmed) return { mode: "disconnected" };
    return { mode: "connected" };
  }

  return {
    beginLaunchSession({ now }) {
      sawLive = false;
      pendingSince = null;
      disconnectConfirmed = false;
      menuAckCount = 0;
      forcedMode = null;
      return stateAt(now);
    },

    setForcedMode(mode) {
      forcedMode = mode;
      return stateAt(Date.now());
    },

    clearForcedMode() {
      forcedMode = null;
    },

    reset() {
      sawLive = false;
      pendingSince = null;
      disconnectConfirmed = false;
      menuAckCount = 0;
      forcedMode = null;
      return stateAt(0);
    },

    /**
     * @param {'live' | 'non_live' | 'ignore'} kind
     * @param {number} now
     */
    tick(kind, now) {
      if (kind === "ignore") return stateAt(now);
      if (kind === "live") {
        sawLive = true;
        pendingSince = null;
        disconnectConfirmed = false;
        menuAckCount = 0;
        return { mode: "connected" };
      }
      if (!sawLive) return stateAt(now);
      if (kind === "non_live") {
        menuAckCount += 1;
        if (pendingSince === null) {
          pendingSince = now;
          disconnectConfirmed = false;
          return { mode: "connected" };
        }
        if (menuAckCount >= 2 && now - pendingSince >= graceMs) {
          disconnectConfirmed = true;
          return { mode: "disconnected" };
        }
        return { mode: "connected" };
      }
      return stateAt(now);
    },

    sessionEnded(reason) {
      forcedMode = reason === "game_session_changed" ? "session_changed" : "session_ended";
      sawLive = false;
      pendingSince = null;
      disconnectConfirmed = false;
      menuAckCount = 0;
      return { mode: forcedMode };
    },

    stateAt(now) {
      return stateAt(now);
    },
  };
}
