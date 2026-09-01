/** Complete v1 lobby snapshot validator for acknowledgement. */

const LIVE = new Set(["LOBBY", "QUEUE", "GAME"]);
const STATES = new Set(["OK", "NICKED", "NEVER_PLAYED", "ERROR", "LOADING"]);

function isFiniteNumber(n) {
  return typeof n === "number" && Number.isFinite(n);
}

function isSafeInt(n) {
  return Number.isInteger(n) && n >= -2147483648 && n <= 2147483647;
}

function isSafeJsPositiveInt(n) {
  return Number.isInteger(n) && n > 0 && n <= Number.MAX_SAFE_INTEGER;
}

function validPlayer(p) {
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
  if (!Array.isArray(p.seraphTags) || !Array.isArray(p.urchinTags)) return false;
  for (const t of p.seraphTags) if (typeof t !== "string") return false;
  for (const t of p.urchinTags) if (typeof t !== "string") return false;
  return true;
}

function validTeam(t) {
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
  if (d.v !== 1) return false;
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
  if (!d.teams.every(validTeam)) return false;
  return true;
}

export function classifyLive(snapshot) {
  if (!isValidLobbySnapshot(snapshot)) return "invalid";
  if (snapshot.inHypixel === true && LIVE.has(snapshot.context)) return "live";
  if (snapshot.inHypixel === false && snapshot.context === "MENU") return "non_live";
  return "other";
}
