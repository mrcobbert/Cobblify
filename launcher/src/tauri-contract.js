/**
 * Sole adapter for Tauri invoke command names, argument keys, and reply tags.
 */

export const CMD = {
  launchPreferences: "launch_preferences",
  setAutoJoinHypixel: "set_auto_join_hypixel",
  launchLunar: "launch_lunar",
  launchForge: "launch_forge",
  lobbyState: "lobby_state",
  acknowledgeLobbySnapshot: "acknowledge_lobby_snapshot",
  launchProgress: "launch_progress",
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

export function launchLunar(invoke, expectedAutoJoinHypixel) {
  return invoke(CMD.launchLunar, { expectedAutoJoinHypixel });
}

export function launchForge(invoke, expectedAutoJoinHypixel) {
  return invoke(CMD.launchForge, { expectedAutoJoinHypixel });
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

/** @typedef {{ autoJoinHypixel: boolean, health: string, diagnostic?: string }} LaunchPreferencesView */
/** @typedef {{ status: 'saved', autoJoinHypixel: boolean } | { status: 'reconciled', autoJoinHypixel: boolean } | { status: 'not_saved', diagnostic: string } | { status: 'indeterminate' }} PreferenceSaveReply */
/** @typedef {{ status: 'launched', outcome: { autoJoinHypixel: boolean, action: string }, generation: number } | { status: 'preexisting_game', preferences: LaunchPreferencesView } | { status: 'rejected', code: string, preferences?: LaunchPreferencesView, message?: string }} LaunchReply */
/** @typedef {{ kind: 'snapshot', snapshot: object, token: number } | { kind: 'unavailable', reason?: string } | { kind: 'session_ended', reason: string }} LobbyPoll */
