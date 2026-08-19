/** Browser-preview fixtures: every shape here must match what the Rust backend emits. */

import { ISSUE } from "./setup-view.js";

const tgt = (kind, state, message, extra = {}) =>
  Object.assign(
    {
      kind,
      state,
      issue: null,
      message,
      detail: null,
      action: "none",
      candidates: [],
      quarantined_names: [],
      has_setup_folder: false,
    },
    extra,
  );

const PRISM_CANDIDATES = [
  { id: "d0", name: "Hypixel-1.8.9" },
  { id: "d1", name: "Skyblock-1.8.9" },
];

const LUNAR_READY = tgt("lunar", "ready", "Lunar Client");
const LUNAR_ABSENT = tgt("lunar", "absent", "Lunar Client is not installed.", {
  issue: ISSUE.MISSING_LAUNCHER,
});
const LUNAR_UNINITIALIZED = tgt("lunar", "blocked", "Run Lunar once, then reopen Cobblify.", {
  issue: ISSUE.UNINITIALIZED_LUNAR,
});
const LUNAR_RUNNING = tgt(
  "lunar",
  "blocked",
  "Quit Lunar completely with ⌘Q, then return to Cobblify.",
  { issue: ISSUE.RUNNING_LUNAR },
);
const LUNAR_ERROR = tgt("lunar", "error", "Setup failed.", {
  issue: ISSUE.SETUP_ERROR,
  detail: "The bundled Lunar jar is corrupt.",
});

const FORGE_READY = tgt("forge", "ready", "Prism Forge 1.8.9");
const FORGE_READY_RENAMED = tgt("forge", "ready", "Prism Forge 1.8.9", {
  issue: ISSUE.RENAMED_JAR,
  detail: "Cobblify-1.8.9-forge-0.8.0.jar.cobblify-disabled",
  quarantined_names: ["Cobblify-1.8.9-forge-0.8.0.jar.cobblify-disabled"],
  has_setup_folder: true,
});
const FORGE_BLOCKED = tgt("forge", "blocked", "Conflicting jars.", {
  issue: ISSUE.CONFLICTS,
  has_setup_folder: true,
});
const FORGE_ERROR = tgt("forge", "error", "Setup failed.", {
  issue: ISSUE.SETUP_ERROR,
  detail: "Cannot create mods: permission denied.",
});
const FORGE_CHOOSE = tgt("forge", "absent", "Choose a Prism instance.", {
  action: "choose",
  candidates: PRISM_CANDIDATES,
});
const FORGE_NO_COMPAT = tgt("forge", "absent", "No compatible Prism instance was found.", {
  issue: ISSUE.NO_COMPATIBLE_PRISM,
});
const FORGE_NO_PRISM = tgt("forge", "absent", "Prism Launcher is not installed.", {
  issue: ISSUE.MISSING_LAUNCHER,
});
const FORGE_NO_JAR = tgt("forge", "absent", "This copy does not include Forge.", {
  issue: ISSUE.MISSING_BUNDLED_FORGE,
});

export const PREVIEW_STATUS = {
  lunarReady: {
    state: "ready",
    message:
      "Cobblify v0.9.0 ready for Lunar Client - Right Shift for settings in game",
    mod_version: "0.9.0",
    targets: [LUNAR_READY, FORGE_CHOOSE],
  },
  bothReady: {
    state: "ready",
    message:
      "Cobblify v0.9.0 ready for Lunar Client and Prism Forge 1.8.9 - Right Shift for settings in game",
    mod_version: "0.9.0",
    targets: [LUNAR_READY, FORGE_READY],
  },
  lunarBlocked: {
    state: "blocked",
    message: "Quit Lunar completely with ⌘Q, then return to Cobblify.",
    mod_version: "0.9.0",
    targets: [LUNAR_RUNNING, FORGE_NO_JAR],
  },
  lunarUninitialized: {
    state: "blocked",
    message: "Run Lunar once, then reopen Cobblify.",
    mod_version: "0.9.0",
    targets: [LUNAR_UNINITIALIZED, FORGE_CHOOSE],
  },
  lunarAbsent: {
    state: "blocked",
    message: "Choose a Prism instance.",
    mod_version: "0.9.0",
    targets: [LUNAR_ABSENT, FORGE_CHOOSE],
  },
  lunarError: {
    state: "error",
    message: "Setup failed.",
    mod_version: "0.9.0",
    targets: [LUNAR_ERROR, FORGE_CHOOSE],
  },
  lunarJars: {
    state: "blocked",
    message: "Conflicting jars.",
    mod_version: "0.9.0",
    targets: [
      tgt("lunar", "blocked", "Conflicting jars.", {
        issue: ISSUE.CONFLICTS,
        has_setup_folder: true,
      }),
      FORGE_NO_JAR,
    ],
  },
  prismChoose: {
    state: "blocked",
    message: "Choose a Prism instance.",
    mod_version: "0.9.0",
    targets: [LUNAR_ABSENT, FORGE_CHOOSE],
  },
  prismNoInstances: {
    state: "blocked",
    message: "Install Prism Launcher to set up Forge.",
    mod_version: "0.9.0",
    targets: [LUNAR_ABSENT, FORGE_NO_PRISM],
  },
  prismNoCompatible: {
    state: "blocked",
    message: "No compatible Prism instance was found.",
    mod_version: "0.9.0",
    targets: [LUNAR_ABSENT, FORGE_NO_COMPAT],
  },
  forgeReady: {
    state: "ready",
    message:
      "Cobblify v0.9.0 ready for Prism Forge 1.8.9 - Right Shift for settings in game",
    mod_version: "0.9.0",
    targets: [LUNAR_ABSENT, FORGE_READY],
  },
  forgeReadyRenamed: {
    state: "ready",
    message:
      "Cobblify v0.9.0 ready for Lunar Client and Prism Forge 1.8.9 - Right Shift for settings in game",
    mod_version: "0.9.0",
    targets: [LUNAR_READY, FORGE_READY_RENAMED],
  },
  forgeBlocked: {
    state: "blocked",
    message: "Conflicting jars.",
    mod_version: "0.9.0",
    targets: [LUNAR_ABSENT, FORGE_BLOCKED],
  },
  forgeError: {
    state: "error",
    message: "Setup failed.",
    mod_version: "0.9.0",
    targets: [LUNAR_ABSENT, FORGE_ERROR],
  },
  dualConflict: {
    state: "blocked",
    message: "Conflicting jars.",
    mod_version: "0.9.0",
    targets: [
      tgt("lunar", "blocked", "Conflicting jars.", {
        issue: ISSUE.CONFLICTS,
        has_setup_folder: true,
      }),
      tgt("forge", "blocked", "Conflicting jars.", {
        issue: ISSUE.CONFLICTS,
        has_setup_folder: true,
      }),
    ],
  },
  mixedReady: {
    state: "ready",
    message:
      "Cobblify v0.9.0 ready for Lunar Client - Right Shift for settings in game",
    mod_version: "0.9.0",
    targets: [LUNAR_READY, FORGE_CHOOSE],
  },
  noForgeJar: {
    state: "blocked",
    message: "This copy does not include Forge.",
    mod_version: "0.8.0",
    targets: [LUNAR_ABSENT, FORGE_NO_JAR],
  },
  bundleError: {
    state: "error",
    message: "Setup files are missing.",
    mod_version: null,
    targets: [],
  },
};

export const PREVIEW_SETUP_KEYS = [
  "lunarReady",
  "lunarBlocked",
  "lunarUninitialized",
  "lunarAbsent",
  "lunarError",
  "lunarJars",
  "prismChoose",
  "prismNoInstances",
  "prismNoCompatible",
  "forgeReady",
  "forgeReadyRenamed",
  "forgeBlocked",
  "forgeError",
  "dualConflict",
  "mixedReady",
  "bothReady",
  "noForgeJar",
  "bundleError",
];

export const PREVIEW_SETUP_LABELS = {
  lunarReady: "Lunar ready",
  lunarBlocked: "Quit Lunar",
  lunarUninitialized: "Run Lunar once",
  lunarAbsent: "No Lunar",
  lunarError: "Lunar error",
  lunarJars: "Jar conflict",
  prismChoose: "Choose Prism",
  prismNoInstances: "No Prism",
  prismNoCompatible: "Bad Prism",
  forgeReady: "Prism ready",
  forgeReadyRenamed: "Renamed jar",
  forgeBlocked: "Prism blocked",
  forgeError: "Prism error",
  dualConflict: "Both conflict",
  mixedReady: "Mixed ready",
  bothReady: "Both ready",
  noForgeJar: "No Forge",
  bundleError: "Bundle error",
};

export const PREVIEW_STATUS_ALIASES = {
  ready: "lunarReady",
  lunar: "lunarBlocked",
  jars: "lunarJars",
  forge: "prismChoose",
  both: "bothReady",
  oldbundle: "noForgeJar",
  error: "bundleError",
  prismIncompatible: "prismNoCompatible",
};

export function resolvePreviewStatus(key) {
  const canonical = PREVIEW_STATUS_ALIASES[key] ?? key;
  return PREVIEW_STATUS[canonical] ?? PREVIEW_STATUS.lunarReady;
}

const TOP_LEVEL_STATES = new Set(["ready", "blocked", "error"]);
const TARGET_KINDS = new Set(["lunar", "forge"]);
const TARGET_STATES = new Set(["ready", "blocked", "absent", "error"]);
const TARGET_ACTIONS = new Set(["none", "choose"]);
const ISSUE_CODES = new Set(Object.values(ISSUE));

export function assertBackendShapedStatus(status) {
  if (!status || typeof status !== "object") throw new Error("status must be an object");
  if (!TOP_LEVEL_STATES.has(status.state)) {
    throw new Error(`invalid top-level state: ${status.state}`);
  }
  if (typeof status.message !== "string") throw new Error("message must be a string");
  if (!("mod_version" in status)) throw new Error("mod_version is required");
  if (!Array.isArray(status.targets)) throw new Error("targets must be an array");

  for (const t of status.targets) {
    if (!TARGET_KINDS.has(t.kind)) throw new Error(`invalid target kind: ${t.kind}`);
    if (!TARGET_STATES.has(t.state)) throw new Error(`invalid target state: ${t.state}`);
    if (typeof t.message !== "string") throw new Error("target.message must be a string");
    if (t.issue != null && !ISSUE_CODES.has(t.issue)) {
      throw new Error(`invalid target issue: ${t.issue}`);
    }
    if (!TARGET_ACTIONS.has(t.action)) throw new Error(`invalid target action: ${t.action}`);
    if (!Array.isArray(t.candidates)) throw new Error("target.candidates must be an array");
    if (!Array.isArray(t.quarantined_names)) {
      throw new Error("target.quarantined_names must be an array");
    }
    if (typeof t.has_setup_folder !== "boolean") {
      throw new Error("target.has_setup_folder must be a boolean");
    }
    for (const c of t.candidates) assertPrismCandidate(c);
  }
}

export function assertPrismCandidate(c) {
  if (typeof c.id !== "string") throw new Error("candidate.id must be a string");
  if (typeof c.name !== "string") throw new Error("candidate.name must be a string");
  for (const key of Object.keys(c)) {
    if (key !== "id" && key !== "name") {
      throw new Error(`candidate must only expose id and name, found ${key}`);
    }
  }
}

export function collectPreviewCandidates() {
  const out = [];
  for (const status of Object.values(PREVIEW_STATUS)) {
    for (const t of status.targets ?? []) {
      out.push(...(t.candidates ?? []));
    }
  }
  return out;
}

export function aggregateStatus(modVersion, targets) {
  const ready = targets.filter((t) => t.state === "ready");

  if (ready.length > 0) {
    const names = ready.map((t) => t.message);
    const v = modVersion ?? "";
    return {
      state: "ready",
      message: `Cobblify v${v} ready for ${names.join(" and ")} - Right Shift for settings in game`,
      mod_version: modVersion,
      targets,
    };
  }

  const blocked = targets.find((t) => t.state === "blocked");
  if (blocked) {
    return {
      state: "blocked",
      message: blocked.message,
      mod_version: modVersion,
      targets,
    };
  }

  const err = targets.find((t) => t.state === "error");
  if (err) {
    return {
      state: "error",
      message: err.message,
      mod_version: modVersion,
      targets,
    };
  }

  const forge = targets.find((t) => t.kind === "forge");
  let message = "Setup needed.";
  if (forge?.issue === ISSUE.MISSING_LAUNCHER) message = "Install Prism Launcher to set up Forge.";
  else if (forge?.issue === ISSUE.NO_COMPATIBLE_PRISM) {
    message = "No compatible Prism instance was found.";
  } else if (forge?.action === "choose") message = "Choose a Prism instance.";
  else if (forge?.issue === ISSUE.MISSING_BUNDLED_FORGE) {
    message = "This copy does not include Forge.";
  }

  return {
    state: "blocked",
    message,
    mod_version: modVersion,
    targets,
  };
}
