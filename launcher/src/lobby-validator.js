/**
 * Lobby snapshot validator for acknowledgement.
 *
 * Two shapes are recognised. v2 is the current contract: every presentation decision
 * (tier, cheater, badge, chips, mode, nick reveal) is made in the mod and exported, and
 * the launcher only draws. v1 is the shape older mod jars still write; it is accepted so
 * the writer binding and session tracking keep working, but the dashboard shows an
 * "update Cobblify" note for it instead of a roster (see dashboard-view.js).
 *
 * The fixtures under common/src/test/resources/lobby-contract are the shared truth for this
 * file, the Rust validator and the mod's DTO test.
 */

export const CONTRACT_VERSION = 2;

const LIVE = new Set(["LOBBY", "QUEUE", "GAME"]);
const STATES = new Set(["OK", "NICKED", "NEVER_PLAYED", "ERROR", "LOADING"]);
/** Standing in the current game (mod ≥ roster memory). Absent on older mod jars = ACTIVE. */
const PRESENCE = new Set(["ACTIVE", "DISCONNECTED", "ELIMINATED", "MISSING"]);

function isFiniteNumber(n) {
  return typeof n === "number" && Number.isFinite(n);
}

function isSafeInt(n) {
  return Number.isInteger(n) && n >= -2147483648 && n <= 2147483647;
}

function isSafeJsPositiveInt(n) {
  return Number.isInteger(n) && n > 0 && n <= Number.MAX_SAFE_INTEGER;
}

function validCommonPlayer(p) {
  if (!p || typeof p !== "object") return false;
  if (typeof p.name !== "string") return false;
  if (typeof p.state !== "string" || !STATES.has(p.state)) return false;
  if (typeof p.nicked !== "boolean") return false;
  if (!("realName" in p)) return false;
  if (p.realName !== null && typeof p.realName !== "string") return false;
  if (typeof p.rank !== "string") return false;
  if (!isFiniteNumber(p.fkdr) || !isFiniteNumber(p.wlr) || !isFiniteNumber(p.kd)) return false;
  if (!isSafeInt(p.finalKills)) return false;
  if (!isSafeInt(p.seraphThreat)) return false;
  if (p.presence !== undefined && !PRESENCE.has(p.presence)) return false;
  return true;
}

function validPlayerV1(p) {
  if (!validCommonPlayer(p)) return false;
  if (!Array.isArray(p.seraphTags) || !Array.isArray(p.urchinTags)) return false;
  for (const t of p.seraphTags) if (typeof t !== "string") return false;
  for (const t of p.urchinTags) if (typeof t !== "string") return false;
  return true;
}

export function validChip(c) {
  if (!c || typeof c !== "object") return false;
  if (typeof c.code !== "string" || typeof c.color !== "string" || typeof c.label !== "string") return false;
  return typeof c.positive === "boolean";
}

function validPlayerV2(p) {
  if (!validCommonPlayer(p)) return false;
  if (typeof p.rankCodes !== "string") return false;
  if (typeof p.mode !== "string") return false;
  if (!Number.isInteger(p.fkdrTier) || p.fkdrTier < 0 || p.fkdrTier > 3) return false;
  if (typeof p.cheater !== "boolean") return false;
  if (!("badge" in p)) return false;
  if (p.badge !== null && !validChip(p.badge)) return false;
  if (!Array.isArray(p.chips) || !p.chips.every(validChip)) return false;
  return true;
}

function validTeam(t, validPlayer) {
  if (!t || typeof t !== "object") return false;
  if (typeof t.name !== "string") return false;
  if (!Array.isArray(t.players)) return false;
  return t.players.every(validPlayer);
}

/**
 * @param {unknown} d
 * @returns {boolean}
 */
export function isValidLobbySnapshot(d) {
  if (!d || typeof d !== "object") return false;
  const validPlayer = d.v === 2 ? validPlayerV2 : d.v === 1 ? validPlayerV1 : null;
  if (!validPlayer) return false;
  if (!isSafeInt(d.seq)) return false;
  if (!isSafeInt(d.jvmPid) || d.jvmPid <= 0) return false;
  if (!isSafeJsPositiveInt(d.jvmStartTimeMs)) return false;
  const ctx = d.context;
  if (typeof ctx !== "string") return false;
  if (!["MENU", "LOBBY", "QUEUE", "GAME"].includes(ctx)) return false;
  if (typeof d.inHypixel !== "boolean") return false;
  if ("dashboardEligible" in d && typeof d.dashboardEligible !== "boolean") return false;
  if (!("self" in d)) return false;
  if (d.self !== null && typeof d.self !== "string") return false;
  if (!("mode" in d)) return false;
  if (d.mode !== null && typeof d.mode !== "string") return false;
  if (!("partyCount" in d)) return false;
  if (d.partyCount !== null && !isSafeInt(d.partyCount)) return false;
  if (!Array.isArray(d.yourParty) || !Array.isArray(d.players) || !Array.isArray(d.teams)) {
    return false;
  }
  if (!d.yourParty.every(validPlayer)) return false;
  if (!d.players.every(validPlayer)) return false;
  if (!d.teams.every((t) => validTeam(t, validPlayer))) return false;
  return true;
}

/** A well-formed snapshot from a mod jar older than this launcher's contract. */
export function isOutdatedSnapshot(d) {
  return Boolean(d) && typeof d === "object" && Number.isInteger(d.v) && d.v < CONTRACT_VERSION;
}

export function classifyLive(snapshot) {
  if (!isValidLobbySnapshot(snapshot)) return "invalid";
  if (snapshot.inHypixel === true && LIVE.has(snapshot.context)) return "live";
  if (snapshot.inHypixel === false && snapshot.context === "MENU") return "non_live";
  return "other";
}
