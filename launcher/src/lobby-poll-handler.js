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
 *   enterTerminal: (reason: string) => void,
 *   stopLobbyPolling: () => void,
 *   setStageDash: () => void,
 *   now?: number,
 * }} deps
 */
export async function handleLobbyPoll(poll, deps) {
  const now = deps.now ?? Date.now();
  if (!poll || typeof poll !== "object") return;
  if (poll.kind === "session_ended") {
    deps.stopLobbyPolling();
    deps.connection.sessionEnded(poll.reason);
    deps.enterTerminal(poll.reason);
    deps.setStageDash();
    return;
  }
  if (poll.kind === "unavailable") {
    const { mode } = deps.connection.stateAt(now);
    if (mode === "waiting" || mode === "joining") {
      deps.connectionShell.show(mode);
      deps.setStageDash();
    } else if (mode === "manual") {
      deps.connectionShell.show("manual", {
        subtitle: deps.manualRouteSubtitle ?? undefined,
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
  try {
    await deps.launchController.acknowledgeSnapshot(
      poll.token,
      deps.launchController.getState().launchGen,
      snapshot,
    );
  } catch {
    return;
  }
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
  if (mode === "waiting" || mode === "joining") {
    deps.connectionShell.show(mode);
    deps.setStageDash();
  } else if (mode === "manual") {
    deps.connectionShell.show("manual", {
      subtitle: deps.manualRouteSubtitle ?? undefined,
    });
    deps.setStageDash();
  }
}
