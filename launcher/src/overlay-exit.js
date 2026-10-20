/**
 * Overlay-off quit coordination, extracted so the production trigger
 * selection is directly testable. Optional Lunar cleanup finishes before
 * Cobblify exits; no path hides Lunar or Prism.
 *
 * Routes per launch outcome (exhaustive):
 * - overlay on: normal overlay flow for every action; never quits.
 * - overlay off + `launcher_opened` (never-fired open-only): quit
 *   immediately - the backend returns this outcome only when the launcher
 *   was brought forward. With optional Lunar cleanup enabled, keep monitoring
 *   manual Play instead, then finalize after loaded proof.
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
 *   finishLunar?: (generation: number) => Promise<string>,
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
  let finalizePromise = null;

  /**
   * Begin a session from a launched outcome; returns the wiring route.
   * The three wire actions are matched explicitly; an unknown future
   * action deliberately gets the safest route (overlay: app stays open,
   * nothing quits).
   * @param {{ action: string, useExternalOverlay: boolean, quitLunarAfterLaunch?: boolean, generation?: number }} o
   * @returns {'overlay' | 'quit_now' | 'no_dashboard'}
   */
  function routeFor(o) {
    session += 1;
    outcome = o;
    quitFired = false;
    finalizePromise = null;
    if (o.useExternalOverlay) return "overlay";
    if (o.action === "launcher_opened") return o.quitLunarAfterLaunch ? "no_dashboard" : "quit_now";
    if (o.action === "game_launch_requested") return "no_dashboard";
    if (o.action === "launch_unconfirmed") return "overlay"; // recovery surface
    return "overlay";
  }

  function resetSession() {
    session += 1;
    outcome = null;
    quitFired = false;
    finalizePromise = null;
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
    const expected = session;
    if (o.quitLunarAfterLaunch) {
      if (!finalizePromise) {
        finalizePromise = Promise.resolve().then(() => session === expected ? deps.finishLunar?.(o.generation) : null).catch(() => "unavailable");
      }
      const result = await finalizePromise;
      if (session !== expected) return;
      if (result === "not_ready") { finalizePromise = null; return; }
    }
    if (o.useExternalOverlay) return;
    if (o.action === "game_launch_requested" || o.action === "launch_unconfirmed" ||
        (o.action === "launcher_opened" && o.quitLunarAfterLaunch)) {
      await quitNow(expected);
    }
  }

  return { routeFor, resetSession, quitNow, onConfirmationSignal };
}
