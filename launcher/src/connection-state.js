/** @typedef {'manual' | 'joining' | 'waiting' | 'connected' | 'disconnected' | 'preexisting_game' | 'session_ended' | 'session_changed'} ConnectionMode */

export const DISCONNECT_GRACE_MS = 2000;
const WAITING_MS = 60_000;

/**
 * @param {{ graceMs?: number, waitingMs?: number }} [opts]
 */
export function createConnectionModel({ graceMs = DISCONNECT_GRACE_MS, waitingMs = WAITING_MS } = {}) {
  let autoJoinOn = true;
  let sawLive = false;
  let disconnectConfirmed = false;
  /** @type {number | null} */
  let pendingSince = null;
  /** @type {number | null} */
  let waitingDeadline = null;
  /** @type {ConnectionMode} */
  let forcedMode = null;
  let menuAckCount = 0;

  /** @returns {{ mode: ConnectionMode }} */
  function stateAt(now) {
    if (forcedMode) return { mode: forcedMode };
    if (!autoJoinOn && !sawLive) return { mode: "manual" };
    if (autoJoinOn && !sawLive && waitingDeadline != null && now >= waitingDeadline) {
      return { mode: "waiting" };
    }
    if (!sawLive) return { mode: autoJoinOn ? "joining" : "manual" };
    if (pendingSince === null) return { mode: "connected" };
    if (disconnectConfirmed) return { mode: "disconnected" };
    return { mode: "connected" };
  }

  return {
    setAutoJoin(on) {
      autoJoinOn = on;
    },

    beginLaunchSession({ autoJoin, now }) {
      autoJoinOn = autoJoin;
      sawLive = false;
      pendingSince = null;
      disconnectConfirmed = false;
      menuAckCount = 0;
      forcedMode = null;
      waitingDeadline = autoJoin ? now + waitingMs : null;
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
      waitingDeadline = null;
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
        waitingDeadline = null;
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
      waitingDeadline = null;
      return { mode: forcedMode };
    },

    stateAt(now) {
      return stateAt(now);
    },
  };
}
