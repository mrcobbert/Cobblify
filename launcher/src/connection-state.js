/** Live Hypixel contexts from the lobby.json export contract. */
export const LIVE_CONTEXTS = new Set(["LOBBY", "QUEUE", "GAME"]);

/** Contexts the mod may emit; anything else is ignored fail-soft. */
const KNOWN_CONTEXTS = new Set(["MENU", ...LIVE_CONTEXTS]);

/** Grace before a post-live non-live gap becomes a confirmed disconnect. */
export const DISCONNECT_GRACE_MS = 2000;

/**
 * @typedef {'ignore' | 'live' | 'non_live'} SnapshotKind
 * @typedef {'joining' | 'connected' | 'disconnected'} ConnectionMode
 */

/**
 * @param {unknown} d
 * @returns {SnapshotKind}
 */
export function classifySnapshot(d) {
  if (!d || typeof d !== "object") return "ignore";
  const ctx = d.context;
  if (typeof ctx !== "string" || !KNOWN_CONTEXTS.has(ctx)) return "ignore";
  if (d.inHypixel === true && LIVE_CONTEXTS.has(ctx)) return "live";
  if (d.inHypixel === false && ctx === "MENU") return "non_live";
  return "ignore";
}

/**
 * Pure connection model for one launch session. Joining is only the
 * pre-first-live state; after at least one live snapshot, brief non-live gaps
 * stay on the joining interstitial until the grace window elapses.
 *
 * @param {{ graceMs?: number }} [opts]
 */
export function createConnectionModel({ graceMs = DISCONNECT_GRACE_MS } = {}) {
  let sawLive = false;
  let disconnectConfirmed = false;
  /** @type {number | null} */
  let pendingSince = null;

  /** @returns {{ mode: ConnectionMode }} */
  function stateAt(now) {
    if (!sawLive) return { mode: "joining" };
    if (pendingSince === null) return { mode: "connected" };
    if (disconnectConfirmed) return { mode: "disconnected" };
    if (typeof now === "number" && now - pendingSince >= graceMs) {
      disconnectConfirmed = true;
      return { mode: "disconnected" };
    }
    return { mode: "joining" };
  }

  return {
    reset() {
      sawLive = false;
      pendingSince = null;
      disconnectConfirmed = false;
      return stateAt(0);
    },

    /**
     * @param {unknown} snapshot
     * @param {number} now
     */
    tick(snapshot, now) {
      const kind = classifySnapshot(snapshot);
      if (kind === "ignore") return stateAt();

      if (kind === "live") {
        sawLive = true;
        pendingSince = null;
        disconnectConfirmed = false;
        return { mode: "connected" };
      }

      if (!sawLive) return { mode: "joining" };

      if (pendingSince === null) {
        pendingSince = now;
        disconnectConfirmed = false;
      }
      return stateAt(now);
    },
  };
}
