/**
 * Overlay-off quit coordination, extracted so the production trigger
 * selection is directly testable. Cobblify never hides Lunar or Prism, so
 * the only thing to coordinate is when Cobblify itself quits.
 *
 * Routes per launch outcome (exhaustive):
 * - overlay on: normal overlay flow for every action; never quits.
 * - overlay off + `launcher_opened` (never-fired open-only): quit
 *   immediately - the backend returns this outcome only when the launcher
 *   was brought forward.
 * - overlay off + `game_launch_requested`: stay home (headless lobby
 *   polling); progress polling keeps running; quit once Cobblify is known to
 *   have loaded - the backend's `loaded` (a fresh lobby.json from this
 *   launch's game JVM) or an acknowledged live snapshot (itself a lobby.json
 *   from a proven game writer). A quiet log (`settled`) is not proof and
 *   never quits: the didn't-load verdict may still follow it, and an outcome
 *   resets the session instead (home shows it).
 * - overlay off + `launch_unconfirmed`: stay in the overlay flow as the
 *   recovery surface; quit once Cobblify is known to have loaded.
 * - rejected / preexisting_game never reach this module: no session begins.
 *
 * @param {{
 *   quit: () => Promise<void>,
 *   fallbackToOverlayFlow: () => void,
 * }} deps
 */
export function createOverlayExit(deps) {
  // Generation-scoped: every routeFor/resetSession bumps `session`, and
  // every continuation that resumes after an await re-checks it, so a
  // pending quit from session A can never act on session B.
  let session = 0;
  let outcome = null;
  let quitFired = false;

  /**
   * Begin a session from a launched outcome; returns the wiring route.
   * The three wire actions are matched explicitly; an unknown future
   * action deliberately gets the safest route (overlay: app stays open,
   * nothing quits).
   * @param {{ action: string, useExternalOverlay: boolean }} o
   * @returns {'overlay' | 'quit_now' | 'no_dashboard'}
   */
  function routeFor(o) {
    session += 1;
    outcome = o;
    quitFired = false;
    if (o.useExternalOverlay) return "overlay";
    if (o.action === "launcher_opened") return "quit_now";
    if (o.action === "game_launch_requested") return "no_dashboard";
    if (o.action === "launch_unconfirmed") return "overlay"; // recovery surface
    return "overlay";
  }

  function resetSession() {
    session += 1;
    outcome = null;
    quitFired = false;
  }

  /** Quit exactly once; one retry, then fall back to the overlay flow. */
  async function quitNow(expectedSession = session) {
    if (session !== expectedSession || quitFired) return;
    quitFired = true;
    try {
      await deps.quit();
    } catch {
      if (session !== expectedSession) return;
      try {
        await deps.quit();
      } catch {
        if (session !== expectedSession) return;
        quitFired = false;
        deps.fallbackToOverlayFlow();
      }
    }
  }

  /**
   * Proof that Cobblify loaded: `'loaded'` from progress (the backend's
   * proof), `'live'` from the first acknowledged live lobby snapshot (a
   * lobby.json from a proven game writer).
   * @param {'loaded' | 'live'} source
   */
  async function onConfirmationSignal(source) {
    if (!outcome) return;
    if (source !== "loaded" && source !== "live") return;
    const o = outcome;
    if (o.useExternalOverlay) return;
    if (o.action === "game_launch_requested" || o.action === "launch_unconfirmed") {
      await quitNow(session);
    }
  }

  return { routeFor, resetSession, quitNow, onConfirmationSignal };
}
