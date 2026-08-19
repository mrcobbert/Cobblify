/** Maps backend issue codes and target status to compact setup UI. */

export const ISSUE = {
  MISSING_LAUNCHER: "missing_launcher",
  UNINITIALIZED_LUNAR: "uninitialized_lunar",
  RUNNING_LUNAR: "running_lunar",
  CONFLICTS: "conflicts",
  NO_COMPATIBLE_PRISM: "no_compatible_prism_instance",
  MISSING_BUNDLED_FORGE: "missing_bundled_forge_jar",
  SETUP_ERROR: "setup_error",
  RENAMED_JAR: "renamed_jar",
};

const KIND = { lunar: "lunar", forge: "forge" };

export function targetOf(status, kind) {
  return (status.targets ?? []).find((t) => t.kind === kind) ?? null;
}

export function readyTargets(status) {
  return (status.targets ?? []).filter((t) => t.state === "ready");
}

export function setupTargets(status) {
  return (status.targets ?? []).filter((t) => needsSetupAttention(t));
}

export function needsSetupAttention(target) {
  if (!target) return false;
  if (target.state === "ready") {
    return target.issue === ISSUE.RENAMED_JAR || (target.quarantined_names ?? []).length > 0;
  }
  return target.state !== "ready";
}

export function dualConflict(status) {
  const lunar = targetOf(status, KIND.lunar);
  const forge = targetOf(status, KIND.forge);
  return (
    lunar?.issue === ISSUE.CONFLICTS &&
    forge?.issue === ISSUE.CONFLICTS &&
    lunar?.has_setup_folder &&
    forge?.has_setup_folder
  );
}

export function setupHeading(target, status) {
  if (!target) return "Setup";
  if (dualConflict(status) && target.kind === KIND.lunar) return "Conflicting Jars";
  if (dualConflict(status) && target.kind === KIND.forge) return null;

  switch (target.issue) {
    case ISSUE.CONFLICTS:
      return "Conflicting Jars";
    case ISSUE.RUNNING_LUNAR:
      return "Quit Lunar";
    case ISSUE.UNINITIALIZED_LUNAR:
      return "Run Lunar Once";
    case ISSUE.MISSING_LAUNCHER:
      return target.kind === KIND.forge ? "Get Prism" : "Get Lunar";
    case ISSUE.NO_COMPATIBLE_PRISM:
      return "No Compatible Prism Instance";
    case ISSUE.MISSING_BUNDLED_FORGE:
      return "Forge Unavailable";
    case ISSUE.SETUP_ERROR:
      return "Setup Failed";
    case ISSUE.RENAMED_JAR:
      return "Old Jar Disabled";
    default:
      if (target.action === "choose") return "Choose Prism";
      return "Setup";
  }
}

export function setupHint(target) {
  if (target?.issue === ISSUE.RUNNING_LUNAR) return target.message;
  if (target?.issue === ISSUE.UNINITIALIZED_LUNAR) {
    return "Open Lunar, log in once, then come back.";
  }
  return null;
}

export function setupActions(target, status) {
  if (!target || dualConflict(status)) return [];
  const actions = [];

  switch (target.issue) {
    case ISSUE.MISSING_LAUNCHER:
      actions.push({ id: "get", label: target.kind === KIND.forge ? "Get Prism" : "Get Lunar", kind: "download", launcher: target.kind });
      break;
    case ISSUE.UNINITIALIZED_LUNAR:
      actions.push({ id: "open", label: "Open Lunar", kind: "open_launcher", launcher: KIND.lunar });
      break;
    case ISSUE.RUNNING_LUNAR:
      break;
    case ISSUE.NO_COMPATIBLE_PRISM:
      actions.push({ id: "open", label: "Open Prism", kind: "open_launcher", launcher: KIND.forge });
      break;
    case ISSUE.CONFLICTS:
    case ISSUE.RENAMED_JAR:
      if (target.has_setup_folder) {
        actions.push({
          id: "folder",
          label: target.kind === KIND.forge ? "Open Prism Folder" : "Open Lunar Folder",
          kind: "open_folder",
          launcher: target.kind,
        });
      }
      break;
    case ISSUE.SETUP_ERROR:
      actions.push({ id: "retry", label: "Try Again", kind: "refresh" });
      break;
    default:
      break;
  }

  if (target.action === "choose") {
    for (const c of target.candidates ?? []) {
      actions.push({
        id: `setup-${c.id}`,
        label: "Set Up",
        kind: "setup_prism",
        candidateId: c.id,
        candidateName: c.name,
      });
    }
  }

  return actions;
}

export function dualConflictActions() {
  return [
    { id: "lunar-folder", label: "Open Lunar Folder", kind: "open_folder", launcher: KIND.lunar },
    { id: "forge-folder", label: "Open Prism Folder", kind: "open_folder", launcher: KIND.forge },
  ];
}

export function setupBlocks(status) {
  if (dualConflict(status)) {
    return [
      {
        id: "dual-conflict",
        heading: "Conflicting Jars",
        hint: null,
        detail: null,
        candidates: [],
        actions: dualConflictActions(),
        compact: false,
      },
    ];
  }

  const blocks = [];
  for (const target of status.targets ?? []) {
    if (!needsSetupAttention(target)) continue;
    const heading = setupHeading(target, status);
    if (!heading) continue;
    const candidateRows =
      target.action === "choose"
        ? (target.candidates ?? []).map((c) => ({
            id: c.id,
            name: c.name,
            action: { id: `setup-${c.id}`, label: "Set Up", kind: "setup_prism", candidateId: c.id },
          }))
        : [];
    const compatibilityDetail =
      target.issue === ISSUE.NO_COMPATIBLE_PRISM
        ? "Prism instances must use Minecraft 1.8.9 with Forge."
        : null;
    const actions = candidateRows.length ? [] : setupActions(target, status);
    blocks.push({
      id: `${target.kind}-${target.issue ?? target.state}`,
      kind: target.kind,
      heading,
      hint: setupHint(target),
      detail: target.detail ?? null,
      candidates: candidateRows,
      actions,
      compact: target.state === "ready",
      hasDetails:
        Boolean(compatibilityDetail) ||
        Boolean(target.detail) ||
        (target.quarantined_names ?? []).length > 0,
      detailText:
        compatibilityDetail ??
        (target.issue === ISSUE.RENAMED_JAR
          ? (target.quarantined_names ?? []).join(", ")
          : target.detail),
    });
  }
  return blocks;
}

export function topLevelError(status) {
  if (status.state !== "error") return null;
  // Target errors already render as setup blocks with their own retry and
  // details. A second top-level block would duplicate the exact same action.
  if ((status.targets ?? []).some((t) => t.issue === ISSUE.SETUP_ERROR)) return null;
  return {
    heading: "Setup Failed",
    detail: status.message ?? "Setup failed.",
    actions: [{ id: "retry", label: "Try Again", kind: "refresh" }],
  };
}

export function shouldShowSetup(status) {
  return setupBlocks(status).length > 0 || Boolean(topLevelError(status));
}

export function launchLabel(kind) {
  return kind === KIND.forge ? "Launch Prism" : "Launch Lunar";
}
