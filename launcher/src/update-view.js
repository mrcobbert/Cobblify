/**
 * Pure projection of the backend-owned updater snapshot into the compact
 * launcher row. Copy is fixed per state so a demo fixture and a live
 * backend snapshot of the same state render identically. Release notes
 * never enter this row.
 */
function autoToggle(enabled) {
  return {
    secondaryAction: enabled ? "disable" : "enable",
    secondaryLabel: enabled ? "Disable" : "Enable",
  };
}

export function updateView(snapshot) {
  if (!snapshot.autoUpdatePrompted && snapshot.critical !== true) {
    return {
      kind: "consent",
      title: "Stay current?",
      detail: "Quiet updates.",
      primaryLabel: "Enable",
      primaryAction: "enable",
      secondaryLabel: "Skip",
      secondaryAction: "dismiss_consent",
      blocksLaunch: false,
    };
  }

  const common = {
    kind: snapshot.state,
    detail: "",
    secondaryAction: null,
    secondaryLabel: null,
    blocksLaunch: snapshot.critical === true && snapshot.state !== "installing",
  };
  const version = snapshot.availableVersion || snapshot.currentVersion || "";
  switch (snapshot.state) {
    case "checking":
      return { ...common, title: "Checking…", primaryAction: null, primaryLabel: null };
    case "available":
      return {
        ...common,
        title: `${version} available`,
        primaryAction: "download",
        primaryLabel: "Update",
        ...autoToggle(snapshot.autoUpdateEnabled),
      };
    case "critical_required":
      return {
        ...common,
        title: `${version} required`,
        primaryAction: snapshot.downloadedBytes > 0 ? "resume" : "download",
        primaryLabel: snapshot.downloadedBytes > 0 ? "Resume" : "Update",
      };
    case "downloading": {
      const pct = snapshot.sizeBytes > 0
        ? Math.min(100, Math.round((snapshot.downloadedBytes / snapshot.sizeBytes) * 100))
        : null;
      return {
        ...common,
        title: pct == null ? "Downloading…" : `${pct}%`,
        primaryAction: "pause",
        primaryLabel: "Pause",
      };
    }
    case "paused":
      return {
        ...common,
        title: "Paused",
        detail: snapshot.pauseReason === "game_active" ? "Resumes after game." : "Progress saved.",
        primaryAction: "resume",
        primaryLabel: "Resume",
      };
    case "ready":
      return {
        ...common,
        title: `${version} downloaded`,
        primaryAction: "install",
        primaryLabel: "Restart",
        secondaryAction: "defer",
        secondaryLabel: "Later",
      };
    case "installing":
      return { ...common, title: "Installing…", primaryAction: null, primaryLabel: null };
    case "error":
      return {
        ...common,
        title: snapshot.manual ? "Check failed" : "Unavailable",
        primaryAction: "check",
        primaryLabel: "Retry",
      };
    case "current":
    default:
      return {
        ...common,
        kind: "current",
        title: "Up to date",
        detail: snapshot.currentVersion || "",
        primaryAction: "check",
        primaryLabel: "Check",
        ...autoToggle(snapshot.autoUpdateEnabled),
      };
  }
}
