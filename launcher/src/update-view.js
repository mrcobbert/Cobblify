/**
 * Pure projection of the backend-owned updater snapshot into the compact
 * launcher row. Copy is fixed per state so a demo fixture and a live
 * backend snapshot of the same state render identically. Release notes
 * never enter this row.
 */
/** Dev-channel builds are versioned `<next>-dev.<run>`; the row says so instead of hiding it. */
const DEV_BUILD_RE = /-dev\.\d+$/;
export const DEV_BUILD_LABEL = "dev build";

function isDevBuild(version) {
  return typeof version === "string" && DEV_BUILD_RE.test(version);
}

function autoToggle(enabled) {
  return {
    secondaryAction: enabled ? "disable" : "enable",
    secondaryLabel: enabled ? "Disable" : "Enable",
  };
}

export function updateView(snapshot) {
  const common = {
    kind: snapshot.state,
    detail: "",
    secondaryAction: null,
    secondaryLabel: null,
    blocksLaunch: snapshot.critical === true && snapshot.state !== "installing",
  };

  // A failure outranks the consent gate. A check or an install that failed
  // before the first-run choice was made used to render "Stay current?",
  // which has no Retry and no way back to the error the backend reported.
  if (snapshot.state === "error") {
    return {
      ...common,
      title: snapshot.manual ? "Check failed" : "Unavailable",
      // The backend's own reason, so a refused action is readable in the row.
      detail: snapshot.message ?? "",
      primaryAction: "check",
      primaryLabel: "Retry",
    };
  }

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

  const version = snapshot.availableVersion || snapshot.currentVersion || "";
  switch (snapshot.state) {
    case "checking":
      return { ...common, title: "Checking…", primaryAction: null, primaryLabel: null };
    case "available":
      return {
        ...common,
        title: `${version} available`,
        detail: isDevBuild(version) ? DEV_BUILD_LABEL : "",
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
    case "current":
    default:
      return {
        ...common,
        kind: "current",
        title: "Up to date",
        detail: isDevBuild(snapshot.currentVersion)
          ? `${snapshot.currentVersion} · ${DEV_BUILD_LABEL}`
          : snapshot.currentVersion || "",
        primaryAction: "check",
        primaryLabel: "Check",
        ...autoToggle(snapshot.autoUpdateEnabled),
      };
  }
}
