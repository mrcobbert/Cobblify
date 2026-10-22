/**
 * Pure launch view routing: where the app goes right after a `launched`
 * reply, for every (action x overlay) combination.
 *
 * The rule is one line: a confirmed game dispatch NEVER leaves the home
 * view on its own. The launch key narrates progress until Cobblify reports in
 * (`loaded`, or an acknowledged snapshot of this game); then the dashboard asks
 * the player to join Hypixel (launches never join a server; the player joins
 * Hypixel themselves) and becomes the board once they are on it. The
 * two degraded outcomes keep their manual recovery screen, because that
 * screen IS the guidance ("press Play in Lunar").
 *
 * Overlay state never changes the view: it only decides whether the app
 * quits itself afterwards (see overlay-exit.js).
 *
 * An unknown future action gets the safest route - stay home, no claim the
 * caller cannot back up; a live snapshot still opens the dashboard.
 *
 * @param {{ action: string, useExternalOverlay?: boolean }} outcome
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
