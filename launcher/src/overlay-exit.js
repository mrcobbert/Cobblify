/**
 * Overlay-off quit and late-success rehide coordination, extracted so the
 * production trigger selection is directly testable.
 *
 * Routes per launch outcome (exhaustive):
 * - overlay on: normal overlay flow for every action.
 * - overlay off + `launcher_opened` (never-fired open-only): quit
 *   immediately - the backend returns this outcome only when the launcher
 *   is proven presented and no hide work is in flight.
 * - overlay off + `game_launch_requested`: stay home (headless lobby
 *   polling); progress polling keeps running; quit once Cobblify is known to
 *   have loaded - the backend's `loaded` (a fresh lobby.json from this
 *   launch's game JVM) or an acknowledged live snapshot (itself a lobby.json
 *   from a proven game writer). `settled` is cosmetic (a quiet log after
 *   mixins) and never quits: the didn't-load verdict may still follow it, and
 *   an outcome resets the session instead (home shows it).
 * - overlay off + `launch_unconfirmed`: stay in the overlay flow as the
 *   recovery surface; on the FIRST of settled, loaded or an acknowledged live
 *   snapshot, invoke the rehide command (single-flight across the signals);
 *   quit only after its reply AND once Cobblify is known to have loaded.
 * - rejected / preexisting_game never reach this module: no session begins.
 *
 * @param {{
 *   rehide: (useExternalOverlay: boolean) => Promise<void>,
 *   quit: () => Promise<void>,
 *   fallbackToOverlayFlow: () => void,
 * }} deps
 */
export function createOverlayExit(deps) {
  // Generation-scoped: every routeFor/resetSession bumps `session`, and
  // every continuation that resumes after an await re-checks it, so a
  // pending rehide/quit from session A can never act on session B.
  let session = 0;
  let outcome = null;
  let quitFired = false;
  let rehidePromise = null;
  /** This session saw proof that Cobblify loaded (`loaded` or `live`). */
  let loadedSeen = false;

  /**
   * Begin a session from a launched outcome; returns the wiring route.
   * The three wire actions are matched explicitly; an unknown future
   * action deliberately gets the safest route (overlay: app stays open,
   * nothing quits or rehides).
   * @param {{ action: string, useExternalOverlay: boolean }} o
   * @returns {'overlay' | 'quit_now' | 'no_dashboard'}
   */
  function routeFor(o) {
    session += 1;
    outcome = o;
    quitFired = false;
    rehidePromise = null;
    loadedSeen = false;
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
    rehidePromise = null;
    loadedSeen = false;
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
   * Single-flight rehide shared by both confirmation signals. The retry
   * continuation is session-guarded too: a rejection that resolves after
   * a reset/relaunch must not spawn hide work into the new session.
   */
  function rehideOnce(overlay, expectedSession) {
    if (!rehidePromise) {
      rehidePromise = (async () => {
        try {
          await deps.rehide(overlay);
        } catch {
          if (session !== expectedSession) return;
          try {
            await deps.rehide(overlay);
          } catch {
            // Fail-soft terminal: overlay-on makes no further attempts;
            // overlay-off still exits (the launch is proven).
          }
        }
      })();
    }
    return rehidePromise;
  }

  /**
   * A confirmation signal: `'settled'` from progress (cosmetic: the log went
   * quiet), `'loaded'` from progress (the backend's proof that Cobblify
   * loaded), `'live'` from the first acknowledged live lobby snapshot (also
   * proof: a lobby.json from a proven game writer).
   * @param {'settled' | 'loaded' | 'live'} source
   */
  async function onConfirmationSignal(source) {
    if (!outcome) return;
    const mySession = session;
    const o = outcome;
    if (source === "loaded" || source === "live") loadedSeen = true;
    if (o.action === "launch_unconfirmed") {
      await rehideOnce(o.useExternalOverlay, mySession);
      if (session !== mySession) return; // reset/relaunch superseded us
      if (!o.useExternalOverlay && loadedSeen) await quitNow(mySession);
      return;
    }
    if (!o.useExternalOverlay && o.action === "game_launch_requested" && loadedSeen) {
      await quitNow(mySession);
    }
  }

  return { routeFor, resetSession, quitNow, onConfirmationSignal };
}
