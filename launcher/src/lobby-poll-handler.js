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
 *   homeUntilLive?: boolean,
 *   firstLiveSeen: boolean,
 *   applyLobbySnapshot: (snapshot: object, opts?: object) => void,
 *   resetToHome: (reason: string) => void | Promise<void>,
 *   stopLobbyPolling: () => void,
 *   setStageDash: () => void,
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
    if ((mode === "joining" || mode === "waiting") && deps.homeUntilLive) {
      // Launch in flight with the home view held: the launch button narrates
      // progress until a live snapshot opens the dashboard. The 60s waiting
      // escape hatch is opened ONLY by its explicit deadline (armed solely
      // for overlay-on sessions) - this poll path must never open a dash on
      // the headless overlay-off route.
      return;
    }
    if (mode === "waiting" || mode === "joining") {
      deps.connectionShell.show(mode);
      deps.setStageDash();
    } else if (mode === "manual" && deps.manualRouteSubtitle != null) {
      // A null subtitle means the direct-launch route: stay on the home
      // page (button progress) until a live snapshot opens the dashboard.
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
  if ((mode === "joining" || mode === "waiting") && deps.homeUntilLive) return;
  if (mode === "waiting" || mode === "joining") {
    deps.connectionShell.show(mode);
    deps.setStageDash();
  } else if (mode === "manual" && deps.manualRouteSubtitle != null) {
    deps.connectionShell.show("manual", {
      subtitle: deps.manualRouteSubtitle,
    });
    deps.setStageDash();
  }
}
