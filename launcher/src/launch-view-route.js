/**
 * Pure launch view routing: where the app goes right after a `launched`
 * reply, for every (action x auto-join x overlay) combination.
 *
 * The rule is one line: a confirmed game dispatch NEVER leaves the home
 * view. Both auto-join states narrate progress in the launch button and the
 * dashboard opens only on the first acknowledged live lobby snapshot. The
 * two degraded outcomes keep their manual recovery screen, because that
 * screen IS the guidance ("press Play in Lunar").
 *
 * Overlay state never changes the view: it only decides whether the app
 * quits itself afterwards (see overlay-exit.js).
 *
 * An unknown future action gets the safest route - stay home, no claim the
 * caller cannot back up; a live snapshot still opens the dashboard.
 *
 * @param {{ action: string, autoJoinHypixel?: boolean, useExternalOverlay?: boolean }} outcome
 * @returns {{ stayHome: boolean, shellMode: 'manual' | null, subtitleKind: string | null }}
 */
export function launchViewRoute(outcome) {
  const action = outcome?.action;
  if (action === "launcher_opened" || action === "launch_unconfirmed") {
    return { stayHome: false, shellMode: "manual", subtitleKind: action };
  }
  return { stayHome: true, shellMode: null, subtitleKind: null };
}

/**
 * Visibility of the home-view "Cancel launch" escape control.
 *
 * It exists for exactly one window: a launch that has been dispatched but
 * has not produced a live lobby snapshot yet — the window in which quitting
 * the game used to wedge the app. `homeUntilLive` IS that window (it is
 * cleared by the first live snapshot), so once the dashboard is up the
 * control is gone. An in-flight reset already owns the surface, and an idle
 * home has nothing to cancel.
 *
 * @param {{ activeOutcome: object | null, launchPhase: string, homeUntilLive: boolean, resetActive: boolean }} args
 */
export function showsCancelLaunch({ activeOutcome, launchPhase, homeUntilLive, resetActive }) {
  if (resetActive) return false;
  if (!activeOutcome) return false;
  if (launchPhase === "idle") return false;
  return homeUntilLive === true;
}

/**
 * True while the launch button of `kind` is narrating backend launch stages,
 * so a re-render must leave its text alone. Stage text is strictly more
 * informative than the generic phase label, and a `notify()` from any
 * unrelated state change would otherwise reset it mid-launch.
 *
 * @param {{ launchPhase: string, narratingKind: string | null, kind: string }} args
 */
export function keepsStageNarration({ launchPhase, narratingKind, kind }) {
  return launchPhase === "loading" && narratingKind === kind;
}
