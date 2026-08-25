/**
 * Sole adapter for Tauri invoke command names, argument keys, and reply tags.
 */

export const CMD = {
  launchPreferences: "launch_preferences",
  setAutoJoinHypixel: "set_auto_join_hypixel",
  setUseExternalOverlay: "set_use_external_overlay",
  launchLunar: "launch_lunar",
  launchForge: "launch_forge",
  lobbyState: "lobby_state",
  acknowledgeLobbySnapshot: "acknowledge_lobby_snapshot",
  launchProgress: "launch_progress",
  resetSessionEnd: "reset_session_end",
  abortLaunchSession: "abort_launch_session",
  rehideAfterConfirmation: "rehide_after_confirmation",
  quitApp: "quit_app",
  refreshSetup: "refresh_setup",
  status: "status",
  chooseForgeTarget: "choose_forge_target",
  getLauncher: "get_launcher",
  openLauncher: "open_launcher",
  openSetupLocation: "open_setup_location",
};

export function launchPreferences(invoke) {
  return invoke(CMD.launchPreferences);
}

export function setAutoJoinHypixel(invoke, enabled) {
  return invoke(CMD.setAutoJoinHypixel, { enabled });
}

export function setUseExternalOverlay(invoke, enabled) {
  return invoke(CMD.setUseExternalOverlay, { enabled });
}

export function launchLunar(invoke, expectedAutoJoinHypixel, expectedUseExternalOverlay) {
  return invoke(CMD.launchLunar, { expectedAutoJoinHypixel, expectedUseExternalOverlay });
}

export function launchForge(invoke, expectedAutoJoinHypixel, expectedUseExternalOverlay) {
  return invoke(CMD.launchForge, { expectedAutoJoinHypixel, expectedUseExternalOverlay });
}

export function resetSessionEnd(invoke) {
  return invoke(CMD.resetSessionEnd);
}

/**
 * Forcing variant of the reset for a user-cancelled launch: argument-free,
 * and answered with the SAME `ResetReply` shape as `reset_session_end`.
 * `not_terminal` cannot occur on this path (the whole point of the abort is
 * that no terminal exists yet) but stays a retryable reply defensively.
 */
export function abortLaunchSession(invoke) {
  return invoke(CMD.abortLaunchSession);
}

export function rehideAfterConfirmation(invoke, useExternalOverlay) {
  return invoke(CMD.rehideAfterConfirmation, { useExternalOverlay });
}

export function quitApp(invoke) {
  return invoke(CMD.quitApp);
}

export function lobbyState(invoke) {
  return invoke(CMD.lobbyState);
}

export function acknowledgeLobbySnapshot(invoke, token, generation, snapshot) {
  return invoke(CMD.acknowledgeLobbySnapshot, { token, generation, snapshot });
}

export function launchProgress(invoke) {
  return invoke(CMD.launchProgress);
}

/** @typedef {{ autoJoinHypixel: boolean, useExternalOverlay: boolean, health: string, diagnostic?: string }} LaunchPreferencesView */
/** @typedef {{ status: 'saved', autoJoinHypixel: boolean, useExternalOverlay: boolean } | { status: 'reconciled', autoJoinHypixel: boolean, useExternalOverlay: boolean } | { status: 'not_saved', diagnostic: string } | { status: 'indeterminate' }} PreferenceSaveReply */
/** @typedef {'stale_preference' | 'launch_cooldown' | string} LaunchRejectionCode */
/** @typedef {{ status: 'launched', outcome: { autoJoinHypixel: boolean, useExternalOverlay: boolean, action: 'game_launch_requested' | 'launcher_opened' | 'launch_unconfirmed' }, generation: number } | { status: 'preexisting_game', preferences: LaunchPreferencesView } | { status: 'rejected', code: LaunchRejectionCode, preferences?: LaunchPreferencesView, message?: string }} LaunchReply */
/** @typedef {{ kind: 'snapshot', snapshot: object, token: number } | { kind: 'unavailable', reason?: string } | { kind: 'session_ended', reason: string }} LobbyPoll */
/** Reply of BOTH `reset_session_end` and `abort_launch_session`. */
/** @typedef {{ status: 'ok', preferences: LaunchPreferencesView } | { status: 'already_reset', preferences: LaunchPreferencesView } | { status: 'not_terminal' } | { status: 'busy' }} ResetReply */
