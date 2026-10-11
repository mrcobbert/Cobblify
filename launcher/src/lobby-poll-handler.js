import { classifyLive, isValidLobbySnapshot } from "./lobby-validator.js";

/**
 * Production lobby poll handler — dependency-injected for Node tests.
 *
 * @param {object} poll
 * @param {{
 *   connection: ReturnType<typeof import('./connection-state.js').createConnectionModel>,
 *   connectionShell: ReturnType<typeof import('./connection-shell.js').createConnectionShell>,
 *   launchController: { acknowledgeSnapshot: Function, getState: Function },
 *   manualRouteSubtitle: string | null,
 *   firstLiveSeen: boolean,
 *   applyLobbySnapshot: (snapshot: object, opts?: object) => void,
 *   resetToHome: (reason: string) => void | Promise<void>,
 *   stopLobbyPolling: () => void,
 *   setStageDash: () => void,
 *   enterGame?: () => void,
 *   now?: number,
 * }} deps
 */
export async function handleLobbyPoll(poll, deps) {
  const now = deps.now ?? Date.now();
  if (!poll || typeof poll !== "object") return;
  if (poll.kind === "session_ended") {
    // The orchestrator stops polling and invalidates before any UI change.
    await deps.resetToHome(poll.reason);
    return;
  }
  if (poll.kind === "unavailable") {
    const { mode } = deps.connection.stateAt(now);
    if (mode === "manual" && deps.manualRouteSubtitle != null) {
      // A null subtitle means the direct-launch route: stay on the home
      // page (the key narrates progress) until a live snapshot opens the
      // dashboard. Launches never join a server, so there is no joining or
      // waiting screen: the player joins Hypixel themselves.
      deps.connectionShell.show("manual", {
        subtitle: deps.manualRouteSubtitle,
      });
      deps.setStageDash();
    }
    return;
  }
  if (poll.kind !== "snapshot" || !poll.snapshot) return;
  const snapshot = poll.snapshot;
  if (!isValidLobbySnapshot(snapshot)) return;
  const kind = classifyLive(snapshot);
  if (kind === "invalid" || kind === "other") return;
  // The generation the ack is for. A session reset invalidates it before any
  // UI change, so an ack that resolves after Cancel/Back-to-Home committed is
  // recognised here and dropped instead of reviving a dead dashboard (J2).
  const generation = deps.launchController.getState().launchGen;
  try {
    await deps.launchController.acknowledgeSnapshot(poll.token, generation, snapshot);
  } catch {
    return;
  }
  if (deps.launchController.getState().launchGen !== generation) return;
  const before = deps.connection.stateAt(now);
  const { mode } = deps.connection.tick(kind === "live" ? "live" : "non_live", now);
  if (mode === "connected" && kind === "live") {
    const reconnect = deps.firstLiveSeen && before.mode === "disconnected";
    deps.applyLobbySnapshot(snapshot, { reconnect });
    return;
  }
  if (mode === "disconnected") {
    deps.connectionShell.enterDisconnected();
    deps.setStageDash();
    return;
  }
  // Before the first live snapshot, an acknowledged snapshot of this game is proof Cobblify loaded (the mod writes
  // lobby.json from the title screen on): the dashboard's Join Hypixel message (TEST-NOTES 2).
  if (mode === "manual") deps.enterGame?.();
}
