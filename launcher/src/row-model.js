// The pure model of one roster row, built from a v2 lobby.json player. Every
// decision on the row (FKDR tier, cheater, badge, chips, which mode the numbers
// came from, nick reveal) was made in the mod by the same policy the tab list
// and chat use; this file arranges those exported facts into classes, chips
// and cells and derives nothing. main.js turns the model into markup.

import { isActive, presenceBadge } from "./roster-identity.js";

const n2 = (v) => (typeof v === "number" && Number.isFinite(v) ? v.toFixed(2) : "—");
const nInt = (v) =>
  typeof v === "number" && Number.isFinite(v) ? Math.round(v).toLocaleString() : "—";

const CELLS = [
  { cls: "fkdr", width: 28 },
  { cls: "cell-wlr", width: 24 },
  { cls: "cell-finals", width: 34 },
  { cls: "cell-kd", width: 22 },
];

const skeletons = () => CELLS.map((c) => ({ cls: c.cls, skeleton: true, width: c.width }));
const placeholders = (text) => CELLS.map((c) => ({ cls: c.cls, muted: true, text }));

/** "loading" (LOADING or a transient ERROR) · "unresolved" (never played) · "nicked" · "stats". */
export function rowKind(p) {
  const state = p?.state;
  if (state === "LOADING" || state === "ERROR") return "loading";
  if (state === "NEVER_PLAYED") return "unresolved";
  if (state === "NICKED" && !p.realName) return "nicked";
  return "stats";
}

/** Exported tier, clamped to the four classes the stylesheet knows. */
export function tierOf(p) {
  const t = p?.fkdrTier;
  return Number.isInteger(t) ? Math.min(3, Math.max(0, t)) : 0;
}

/** Chips in the order they are drawn: presence, then state, then identity and tags. */
export function rowChips(p) {
  const chips = [];
  const out = presenceBadge(p);
  if (out) chips.push({ cls: "out", text: out });
  const kind = rowKind(p);
  if (kind === "loading") chips.push({ cls: "state", text: "resolving" });
  if (kind === "unresolved") chips.push({ cls: "state", text: "never played" });
  if (kind === "nicked") chips.push({ cls: "nick", text: "Nicked" });
  if (p?.nicked && p.realName) chips.push({ cls: "nick", text: `nick→${p.realName}` });
  if (kind === "stats") {
    for (const c of p?.chips ?? []) {
      chips.push({ cls: c.positive ? "safe" : "cheat", text: c.label, color: c.color });
    }
    if (Number.isInteger(p?.seraphThreat) && p.seraphThreat >= 0) {
      chips.push({ cls: "neutral", text: `threat ${p.seraphThreat}` });
    }
  }
  return chips;
}

export function rowCells(p) {
  switch (rowKind(p)) {
    case "loading":
      return skeletons();
    case "unresolved":
      return placeholders("—");
    case "nicked":
      return placeholders("?");
    default:
      return [
        { cls: "fkdr", text: n2(p.fkdr) },
        { cls: "cell-wlr", text: n2(p.wlr) },
        { cls: "cell-finals", text: nInt(p.finalKills) },
        { cls: "cell-kd", text: n2(p.kd) },
      ];
  }
}

export function rowClasses(p) {
  const kind = rowKind(p);
  const classes = ["prow"];
  if (kind === "loading") classes.push("loading");
  if (kind === "stats") {
    classes.push(`t${tierOf(p)}`);
    if (p?.cheater === true) classes.push("cheater");
  }
  if (!isActive(p)) {
    classes.push("is-out");
    if (p.presence === "ELIMINATED") classes.push("is-eliminated");
  }
  return classes;
}

export function rowModel(p) {
  return { kind: rowKind(p), classes: rowClasses(p), chips: rowChips(p), cells: rowCells(p) };
}

// ── Minecraft § colour codes → spans ─────────────────────────────────────────
// The mod exports the rank with its own colour codes (e.g. "§b[MVP§c+§b]") so a
// player's custom plus colour survives; this only maps each code to a class.

const COLOR_CODES = "0123456789abcdef";

/** Split a §-coded string into [{cls, text}] segments; format codes are dropped, §r resets. */
export function sectionSpans(codes) {
  const s = String(codes ?? "");
  const out = [];
  let cls = "";
  let buf = "";
  const flush = () => {
    if (buf) out.push({ cls, text: buf });
    buf = "";
  };
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === "§" && i + 1 < s.length) {
      const code = s[i + 1].toLowerCase();
      i++;
      if (COLOR_CODES.includes(code)) {
        flush();
        cls = `mc-${code}`;
      } else if (code === "r") {
        flush();
        cls = "";
      }
      continue;
    }
    buf += ch;
  }
  flush();
  return out;
}
