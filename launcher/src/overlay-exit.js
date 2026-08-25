/**
 * Overlay-off quit and late-success rehide coordination, extracted so the
 * production trigger selection is directly testable.
 *
 * Routes per launch outcome (exhaustive):
 * - overlay on: normal overlay flow for every action.
 * - overlay off + `launcher_opened` (never-fired open-only): quit
 *   immediately - the backend returns this outcome only when the launcher
 *   is proven presented and no hide work is in flight.
 * - overlay off + `game_launch_requested`: skip the dashboard (no lobby
 *   polling); progress polling keeps running; quit at settled, once.
 * - overlay off + `launch_unconfirmed`: stay in the overlay flow as the
 *   recovery surface; on the FIRST of settled or an acknowledged live
 *   snapshot, invoke the rehide command (single-flight across both
 *   signals) and quit only after its reply.
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
   * A confirmation signal: `'settled'` from progress, `'live'` from the
   * first acknowledged live lobby snapshot.
   * @param {'settled' | 'live'} source
   */
  async function onConfirmationSignal(source) {
    if (!outcome) return;
    const mySession = session;
    const o = outcome;
    if (o.action === "launch_unconfirmed") {
      await rehideOnce(o.useExternalOverlay, mySession);
      if (session !== mySession) return; // reset/relaunch superseded us
      if (!o.useExternalOverlay) await quitNow(mySession);
      return;
    }
    if (
      !o.useExternalOverlay &&
      o.action === "game_launch_requested" &&
      source === "settled"
    ) {
      await quitNow(mySession);
    }
  }

  return { routeFor, resetSession, quitNow, onConfirmationSignal };
}
