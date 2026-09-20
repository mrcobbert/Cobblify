/** Browser-preview fixtures: every shape here must match the updater snapshot the backend emits, plus the preference fields the controller merges in. */

const UPDATE_STATES = new Set([
  "idle",
  "checking",
  "current",
  "available",
  "downloading",
  "paused",
  "ready",
  "installing",
  "error",
  "critical_required",
]);

const PAUSE_REASONS = new Set(["manual", "game_active"]);

const snap = (extra = {}) =>
  Object.assign(
    {
      state: "current",
      currentVersion: "0.9.1",
      availableVersion: null,
      notes: null,
      releaseNotesUrl: null,
      downloadedBytes: 0,
      sizeBytes: 0,
      critical: false,
      pauseReason: null,
      diagnosticCode: null,
      autoUpdateEnabled: false,
      autoUpdatePrompted: true,
      updateChannel: "stable",
      channelLoaded: true,
      channelSaving: false,
      manual: false,
    },
    extra,
  );

export const PREVIEW_UPDATE = {
  consent: snap({ autoUpdatePrompted: false }),
  checking: snap({ state: "checking" }),
  current: snap(),
  currentAuto: snap({ autoUpdateEnabled: true }),
  currentDev: snap({ currentVersion: "0.10.0-dev.41", updateChannel: "dev" }),
  available: snap({
    state: "available",
    availableVersion: "0.10.0",
  }),
  availableDev: snap({
    state: "available",
    availableVersion: "0.10.0-dev.41",
    updateChannel: "dev",
  }),
  critical: snap({
    state: "critical_required",
    availableVersion: "0.10.0",
    critical: true,
  }),
  downloading: snap({
    state: "downloading",
    availableVersion: "0.10.0",
    downloadedBytes: 42_000_000,
    sizeBytes: 84_000_000,
  }),
  paused: snap({
    state: "paused",
    availableVersion: "0.10.0",
    downloadedBytes: 42_000_000,
    sizeBytes: 84_000_000,
    pauseReason: "manual",
  }),
  pausedGame: snap({
    state: "paused",
    availableVersion: "0.10.0",
    downloadedBytes: 42_000_000,
    sizeBytes: 84_000_000,
    pauseReason: "game_active",
  }),
  updateReady: snap({
    state: "ready",
    availableVersion: "0.10.0",
    downloadedBytes: 84_000_000,
    sizeBytes: 84_000_000,
  }),
  updateReadyCritical: snap({
    state: "ready",
    availableVersion: "0.10.0",
    downloadedBytes: 84_000_000,
    sizeBytes: 84_000_000,
    critical: true,
  }),
  installing: snap({
    state: "installing",
    availableVersion: "0.10.0",
    downloadedBytes: 84_000_000,
    sizeBytes: 84_000_000,
    critical: true,
  }),
  updateError: snap({
    state: "error",
    diagnosticCode: "updater_unconfigured",
    manual: false,
  }),
  updateErrorManual: snap({
    state: "error",
    diagnosticCode: "check_failed",
    manual: true,
  }),
};

export const PREVIEW_UPDATE_KEYS = [
  "consent",
  "checking",
  "current",
  "currentAuto",
  "currentDev",
  "available",
  "availableDev",
  "critical",
  "downloading",
  "paused",
  "pausedGame",
  "updateReady",
  "updateReadyCritical",
  "installing",
  "updateError",
  "updateErrorManual",
];

export const PREVIEW_UPDATE_LABELS = {
  consent: "consent",
  checking: "checking",
  current: "current",
  currentAuto: "auto on",
  currentDev: "dev build",
  available: "available",
  availableDev: "dev available",
  critical: "required",
  downloading: "download",
  paused: "paused",
  pausedGame: "in game",
  updateReady: "restart",
  updateReadyCritical: "must restart",
  installing: "installing",
  updateError: "unavailable",
  updateErrorManual: "retry",
};

export function resolvePreviewUpdate(key) {
  return PREVIEW_UPDATE[key] ?? PREVIEW_UPDATE.consent;
}

export function assertBackendShapedUpdate(snapshot) {
  if (!snapshot || typeof snapshot !== "object") throw new Error("update snapshot must be an object");
  if (!UPDATE_STATES.has(snapshot.state)) throw new Error(`invalid update state: ${snapshot.state}`);
  if (typeof snapshot.currentVersion !== "string") throw new Error("currentVersion must be a string");
  if (snapshot.availableVersion != null && typeof snapshot.availableVersion !== "string") {
    throw new Error("availableVersion must be a string or null");
  }
  if (snapshot.notes != null && typeof snapshot.notes !== "string") {
    throw new Error("notes must be a string or null");
  }
  if (snapshot.releaseNotesUrl != null && typeof snapshot.releaseNotesUrl !== "string") {
    throw new Error("releaseNotesUrl must be a string or null");
  }
  if (typeof snapshot.downloadedBytes !== "number") throw new Error("downloadedBytes must be a number");
  if (typeof snapshot.sizeBytes !== "number") throw new Error("sizeBytes must be a number");
  if (typeof snapshot.critical !== "boolean") throw new Error("critical must be a boolean");
  if (snapshot.pauseReason != null && !PAUSE_REASONS.has(snapshot.pauseReason)) {
    throw new Error(`invalid pauseReason: ${snapshot.pauseReason}`);
  }
  if (snapshot.diagnosticCode != null && typeof snapshot.diagnosticCode !== "string") {
    throw new Error("diagnosticCode must be a string or null");
  }
  if (typeof snapshot.autoUpdateEnabled !== "boolean") {
    throw new Error("autoUpdateEnabled must be a boolean");
  }
  if (typeof snapshot.autoUpdatePrompted !== "boolean") {
    throw new Error("autoUpdatePrompted must be a boolean");
  }
}
