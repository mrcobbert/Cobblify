/**
 * Sole adapter for Tauri invoke command names, argument keys, and reply tags.
 */

export const CMD = {
  launchPreferences: "launch_preferences",
  setUseExternalOverlay: "set_use_external_overlay",
  launchLunar: "launch_lunar",
  launchForge: "launch_forge",
  lobbyState: "lobby_state",
  acknowledgeLobbySnapshot: "acknowledge_lobby_snapshot",
  launchProgress: "launch_progress",
  lunarVersionReady: "lunar_version_ready",
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
  updatePreferences: "update_preferences",
  setAutoUpdate: "set_auto_update",
  setUpdateChannel: "set_update_channel",
  updateStatus: "update_status",
  checkForUpdate: "check_for_update",
  startUpdate: "start_update",
  pauseUpdate: "pause_update",
  resumeUpdate: "resume_update",
  installUpdate: "install_update",
  deferUpdate: "defer_update",
  openInfo: "open_info",
  gameTheme: "game_theme",
  beginSetup: "begin_setup",
  resumeSetup: "resume_setup",
  setupState: "setup_state",
};

/** Tauri event carrying every setup `SetupSnapshot` change (the whole snapshot). */
export const SETUP_EVENT = "setup://progress";

/**
 * Starts setting up one client from check 1. `instanceId` (Prism only) is an id from the
 * forge target's `instances` (most recently played first). Without one, Prism uses its
 * saved or only compatible instance, or makes "Cobblify 1.8.9" when none is compatible;
 * several and no pick rejects with "choose_instance" (show the pick first). Replies with
 * the first snapshot; progress follows on `SETUP_EVENT`. Rejects with "busy" while
 * anything else (another setup included) runs.
 * @param {"lunar"|"forge"} kind
 * @param {string} [instanceId]
 */
export function beginSetup(invoke, kind, instanceId) {
  return invoke(CMD.beginSetup, instanceId === undefined ? { kind } : { kind, instanceId });
}

/**
 * Try again: resumes a failed setup of `kind` at the check it failed at. Rejects with
 * "nothing_to_resume" when the last run of that kind did not fail.
 * @param {"lunar"|"forge"} kind
 */
export function resumeSetup(invoke, kind) {
  return invoke(CMD.resumeSetup, { kind });
}

/** The current `SetupSnapshot`, or null before any setup ran. */
export function setupState(invoke) {
  return invoke(CMD.setupState);
}

/** Fresh install, first open after an update, or neither (captured at startup). */
export function openInfo(invoke) {
  return invoke(CMD.openInfo);
}

/** The accent/font tokens of the client `kind` ("lunar" | "forge"). */
export function gameTheme(invoke, kind) {
  return invoke(CMD.gameTheme, { kind });
}

export function launchPreferences(invoke) {
  return invoke(CMD.launchPreferences);
}

export function setUseExternalOverlay(invoke, enabled) {
  return invoke(CMD.setUseExternalOverlay, { enabled });
}

export function updatePreferences(invoke) {
  return invoke(CMD.updatePreferences);
}

export function setAutoUpdate(invoke, enabled) {
  return invoke(CMD.setAutoUpdate, { enabled });
}

/** @param {"stable"|"dev"} channel */
export function setUpdateChannel(invoke, channel) {
  return invoke(CMD.setUpdateChannel, { channel });
}

export function updateStatus(invoke) {
  return invoke(CMD.updateStatus);
}

export function checkForUpdate(invoke, manual) {
  return invoke(CMD.checkForUpdate, { manual });
}

export function startUpdate(invoke) { return invoke(CMD.startUpdate); }
export function pauseUpdate(invoke) { return invoke(CMD.pauseUpdate); }
export function resumeUpdate(invoke) { return invoke(CMD.resumeUpdate); }
export function installUpdate(invoke) { return invoke(CMD.installUpdate); }
export function deferUpdate(invoke) { return invoke(CMD.deferUpdate); }

/**
 * Launches never join a server: Lunar opens through the bare play link, Prism with
 * `--launch <id>` only. The player joins Hypixel themselves.
 */
export function launchLunar(invoke, expectedUseExternalOverlay) {
  return invoke(CMD.launchLunar, { expectedUseExternalOverlay });
}

export function launchForge(invoke, expectedUseExternalOverlay) {
  return invoke(CMD.launchForge, { expectedUseExternalOverlay });
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

/** @returns {Promise<LaunchProgress>} */
export function launchProgress(invoke) {
  return invoke(CMD.launchProgress);
}

/**
 * While "Set Lunar to 1.8.9" shows, what Lunar is on: `game` (a Lunar game runs), `other` (a saved
 * profile on another version), `ready` (a 1.8.9 profile, no game) or `unknown`.
 * @returns {Promise<'game' | 'other' | 'ready' | 'unknown'>}
 */
export function lunarVersionReady(invoke) {
  return invoke(CMD.lunarVersionReady);
}

/** @typedef {{ useExternalOverlay: boolean, health: string, diagnostic?: string }} LaunchPreferencesView */
/** @typedef {{ autoUpdateEnabled: boolean, autoUpdatePrompted: boolean, health: string, diagnostic?: string }} UpdatePreferencesView */
/** @typedef {'idle'|'checking'|'current'|'available'|'downloading'|'paused'|'ready'|'installing'|'error'|'critical_required'} UpdateState */
/** @typedef {{ status: 'saved', useExternalOverlay: boolean } | { status: 'reconciled', useExternalOverlay: boolean } | { status: 'not_saved', diagnostic: string } | { status: 'indeterminate' }} PreferenceSaveReply */
/** @typedef {'stale_preference' | 'launch_cooldown' | string} LaunchRejectionCode */
/** @typedef {{ status: 'launched', outcome: { useExternalOverlay: boolean, action: 'game_launch_requested' | 'launcher_opened' | 'launch_unconfirmed' }, generation: number } | { status: 'preexisting_game', preferences: LaunchPreferencesView } | { status: 'rejected', code: LaunchRejectionCode, preferences?: LaunchPreferencesView, message?: string }} LaunchReply */
/**
 * `launch_progress`. Stages: fired / attached / discovered / mixing / settled (Lunar),
 * forge_fired / forge_downloading (a made instance's first launch) / forge_attached /
 * forge_discovered / forge_mixing / forge_settled (Prism). `outcome`, once present, ends the
 * launch: `wrong_version` (Lunar is not on 1.8.9, from the version guard's line or Weave's log) or `not_loaded`
 * (Minecraft started, or died, without Cobblify).
 * @typedef {{ stage: string, percent: number, loaded?: boolean, outcome?: 'wrong_version' | 'not_loaded' }} LaunchProgress
 */
/** @typedef {{ kind: 'snapshot', snapshot: object, token: number } | { kind: 'unavailable', reason?: string } | { kind: 'session_ended', reason: string }} LobbyPoll */
/** Reply of BOTH `reset_session_end` and `abort_launch_session`. */
/** @typedef {{ status: 'ok', preferences: LaunchPreferencesView } | { status: 'already_reset', preferences: LaunchPreferencesView } | { status: 'not_terminal' } | { status: 'busy' }} ResetReply */
/** @typedef {{ version: string, updated: boolean, fresh: boolean }} OpenInfo */
/** @typedef {{ accent?: string, font?: 'minecraft'|'modern' }} GameTheme */
/** @typedef {{ gen: number, kind: 'lunar'|'forge', check: number, phase: 'running'|'stop'|'failed'|'done', stop?: 'signin'|'game_open'|'closing_lunar', label?: 'closing_lunar'|'making_instance'|'java', failedAt?: number, message?: string }} SetupSnapshot */
