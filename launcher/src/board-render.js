// The 0.18 stats board: draws a board-model.js view into #dash, the view's name into the header, and scales the
// connection screens in #joining. A port of the approved reference (.ai/research/launcher/board/designs/final-fill):
// Bed Wars' sidebar on home's ground, one line per player, every length a whole number of GUI px (--bp) at a whole
// number of device px. The layout arithmetic is board-layout.js; this file builds markup and measures it.

import { contrast } from "./ui/kit.js";
import { FILLVARS, ZERO, boardBox, fill, floorK, frame, kFor, kForWidth, reserveSpec, snapPx } from "./board-layout.js";
import { boardView, rowCount } from "./board-model.js";
import { PREVIEW_LOBBY } from "./board-fixtures.js";

const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/** Bed Wars teams as the sidebar names them: letter + § code (Hypixel's Gray team is S, §8). */
const TEAM = { Red: ["R", "c"], Blue: ["B", "9"], Green: ["G", "a"], Yellow: ["Y", "e"], Aqua: ["A", "b"], White: ["W", "f"], Pink: ["P", "d"], Gray: ["S", "8"] };
/** The game's own § colours, for fills (text uses the lifted --tN tokens in dash.css). */
const TRUE = { 0: "#000000", 1: "#0000AA", 2: "#00AA00", 3: "#00AAAA", 4: "#AA0000", 5: "#AA00AA", 6: "#FFAA00", 7: "#AAAAAA", 8: "#555555", 9: "#5555FF", a: "#55FF55", b: "#55FFFF", c: "#FF5555", d: "#FF55FF", e: "#FFFF55", f: "#FFFFFF" };
const tx = (c) => (c ? `var(--t${c})` : "var(--t7)");

/** Text with the glyphs the font lacks drawn as pixel masks; the character stays in the DOM for screen readers. */
export function glyphs(s) {
  return esc(s)
    .replace(/—/g, '<span class="pg dsh"><span class="sr">—</span></span>')
    .replace(/→/g, '<span class="pg arr"><span class="sr">→</span></span>')
    .replace(/…/g, '<span class="pg ell"><span class="sr">…</span></span>')
    .replace(/✘/g, '<span class="pg x"><span class="sr">✘</span></span>');
}

/** A screen's sub line; a trailing "…" becomes three dots that step while a reset runs ([aria-busy=true]). */
export function subHTML(s) {
  const text = String(s ?? "");
  if (!text.endsWith("…")) return glyphs(text);
  const dot = (i) => `<span class="dot d${i}" aria-hidden="true">.</span>`;
  return `${glyphs(text.slice(0, -1))}<span class="sr">…</span>${dot(1)}${dot(2)}${dot(3)}`;
}

// ---------- grid assembly: every cell is placed on an explicit row; rows have explicit heights ----------
// columns: 1 letter, 2 team, 3 avg, 4 flag, 5 name, 6 FKDR, 7 WLR, 8 Finals, 9 K/D, 10 notes
const COL = { L: 1, TN: 2, AV: 3, FL: 4, NM: 5, FK: 6, WL: 7, FN: 8, KD: 9, NT: 10, END: 11 };
/** Row heights: a number is fixed GUI px; L a text line (pitch 9 + e), T the gap between teams, P before a section. */
const ROWH = { L: "(9 + var(--e))", T: "(3 + var(--g))", P: "(6 + var(--g))" };
const PITCH = "(9 + var(--e))*var(--bp)";

class Grid {
  constructor() {
    this.rows = [];
    this.cells = [];
    this.lastTile = -1;
    this.pin0 = 0;
  }
  row(h) {
    this.rows.push(h === 9 ? "L" : h);
    return this.rows.length;
  }
  /** Text lines (the head line counts) and gaps: the fill only changes their sizes. */
  counts() {
    const n = { L: 1, G: 0 };
    for (const h of this.rows) if (h === "L") n.L++;
    else if (h === "T" || h === "P") n.G++;
    return n;
  }
  put(r, c0, c1, cls, html, extra = "") {
    this.cells.push({ r, c0, c1, cls, html, extra });
  }
  /** Your party pins to the board's bottom when a long list scrolls. */
  pinFrom() {
    this.pin0 = this.rows.length + 1;
  }
  html(cls) {
    const rows = this.rows.map((h) => `calc(${ROWH[h] || h}*var(--bp))`).join(" ");
    const n = this.counts();
    const pin0 = this.pin0 || 1e9;
    const last = this.rows.length;
    const cells = this.cells.map((c) => {
      if (c.r < pin0) return `<div class="c mc ${c.cls}" style="grid-row:${c.r};grid-column:${c.c0} / ${c.c1}"${c.extra}>${c.html}</div>`;
      // a sticky grid item moves only inside its grid area: each pinned cell spans from the first row to its own
      return `<div class="c mc pin ${c.cls}" style="grid-row:1 / ${c.r + 1};grid-column:${c.c0} / ${c.c1};bottom:calc(${last - c.r}*${PITCH})"${c.extra}>${c.html}</div>`;
    });
    for (let r = pin0; r <= last; r++) {
      cells.unshift(`<div class="pinbg${r === pin0 ? " head" : ""}" style="grid-row:1 / ${r + 1};grid-column:1 / -1;bottom:calc(${last - r}*${PITCH})"></div>`);
    }
    return `<div class="sb ${cls}" data-l="${n.L}" data-g="${n.G}" style="grid-template-rows:${rows}">${cells.join("")}</div>`;
  }
}

function rankHTML(r) {
  if (!r.rank.length) return "";
  return r.rank.map((s) => `<span class="r" style="color:${tx(s.c)}">${esc(s.t)}</span>`).join("") + " ";
}

const badgeOf = (r) => (r.kind === "stats" ? r.chips.find((c) => c.kind.startsWith("badge")) ?? null : null);
const token = (code, mc, label) =>
  `<span class="tok" title="${esc(label)}"><span class="br">[</span><span class="g" style="color:${tx(mc)}">${esc(code)}</span><span class="br">]</span></span>`;

/** The flag slot: the mod's priority badge. A filled tile in its true § colour for a cheater, else the in-game token. */
function flagHTML(r, stack) {
  const b = badgeOf(r);
  if (!b) return "";
  const t = r.tags.find((x) => x.code === b.text);
  const label = t ? t.label : "";
  if (r.cheater && b.kind.includes("cheat")) {
    const hex = TRUE[b.mc] || "#AAAAAA";
    const ink = contrast(hex, "#121212") >= contrast(hex, "#FFFFFF") ? "#121212" : "#FFFFFF";
    return `<span class="ftile${stack ? " stack" : ""}" style="--tf-fill:${hex};color:${ink}"${label ? ` title="${esc(label)}"` : ""}>${esc(b.text)}</span>`;
  }
  return token(b.text, b.mc, label);
}

/** Notes: presence word, nick reveal, tags past the badge, threat and mode; ` · ` between them. */
function notesHTML(r) {
  const out = [];
  const add = (cls, html) => out.push(`<span class="${cls}">${html}</span>`);
  const badge = badgeOf(r)?.text ?? null;
  for (const c of r.chips) if (c.kind === "out") add("pres", esc(c.text));
  for (const c of r.chips) if (c.kind === "nick" && c.text !== "Nicked") add("q", glyphs(c.text));
  const extra = r.tags.filter((t) => t.code !== badge);
  if (extra.length) add("tags", extra.map((t) => token(t.code, t.mc, t.label)).join(" "));
  for (const c of r.chips) if (c.kind === "neutral" || c.kind === "mode") add("q", glyphs(c.text));
  return out.map((s, i) => (i ? `<span class="sep">&nbsp;·&nbsp;</span>` : "") + s).join("");
}

const PH = [16, 14, 20, 12]; // resolving placeholders, GUI px
/** `Nicked` / `never played` / `resolving` are the stat cells' hover title, not words on the row. */
function stateWord(r) {
  const c = r.chips.find((x) => x.kind === "state" || (x.kind === "nick" && x.text === "Nicked"));
  return c ? ` title="${esc(c.text)}"` : "";
}

function statCells(g, line, r, rc) {
  const keys = ["FK", "WL", "FN", "KD"];
  const cls = ["c-fk", "c-wl", "c-fn", "c-kd"];
  keys.forEach((k, i) => {
    const cell = r.cells[i];
    let html;
    let c = `${cls[i]} num ${i ? "pm" : "pw"}${rc}`;
    if (r.kind === "loading" || !cell || cell.text == null) html = `<span class="ph" style="width:calc(${PH[i]}*var(--bp))"></span>`;
    else {
      html = glyphs(cell.text);
      if (cell.muted) c += " mu";
      else if (i === 0) c += ` fk${r.kind === "stats" ? ` t${r.tier}` : ""}`;
      else c += " st";
    }
    g.put(line, COL[k], COL[k] + 1, c, html, stateWord(r));
  });
}

function playerRow(g, r, gutter) {
  const line = g.row(9);
  const rc = (r.out ? " o" : "") + (r.eliminated ? " x" : "");
  const fh = flagHTML(r, g.lastTile === line - 1);
  if (fh.includes('class="ftile')) g.lastTile = line;
  g.put(line, COL.FL, COL.FL + 1, `fl${gutter ? " pm" : ""}${rc}`, fh);
  g.put(line, COL.NM, COL.NM + 1, `pl pr${rc}`, `<span class="strike">${rankHTML(r)}<span class="nm">${esc(r.name)}</span></span>`);
  statCells(g, line, r, rc);
  const tip = r.chips.filter((c) => !c.kind.startsWith("badge")).map((c) => c.text).join(" · ");
  g.put(line, COL.NT, COL.END, `nt pw${rc}`, notesHTML(r), tip ? ` title="${esc(tip)}"` : "");
  return line;
}

// ---------- views ----------
function labels(g, game) {
  const r = g.row(9);
  if (game) g.put(r, COL.TN, COL.AV + 1, "lab pl", "avg");
  g.put(r, COL.FK, COL.FK + 1, "lab num pw c-fk", "FKDR");
  g.put(r, COL.WL, COL.WL + 1, "lab num pm c-wl", "WLR");
  g.put(r, COL.FN, COL.FN + 1, "lab num pm c-fn", "Finals");
  g.put(r, COL.KD, COL.KD + 1, "lab num pm c-kd", "K/D");
}

function sectionLine(g, head, note, game) {
  const r = g.row(9);
  const n = note != null && note !== "" ? ` <span class="n">${esc(note)}</span>` : "";
  g.put(r, game ? COL.L : COL.FL, COL.FK, "", `<h3 class="bsec">${esc(head)}${n}</h3>`);
}

function gameHTML(v) {
  const g = new Grid();
  labels(g, true);
  let first = true;
  for (const b of v.blocks) {
    if (b.kind === "team") {
      g.row(first ? 2 : "T");
      first = false;
      const [letter, code] = TEAM[b.team] || ["?", "7"];
      const target = b.target && b.standing !== "eliminated";
      let r0 = 0;
      b.rows.forEach((row, i) => {
        const r = playerRow(g, row, true);
        if (!i) r0 = r;
      });
      if (!r0) continue;
      if (target && g.rows.length - r0 < 1) g.row(9); // a one-player team still gets its Target line
      const status =
        b.standing === "eliminated"
          ? `<span style="color:var(--tc)" title="Eliminated">${glyphs("✘")}</span>`
          : b.avg == null
            ? `<span style="color:var(--t7)">${glyphs("—")}</span>`
            : esc(b.avg.toFixed(1));
      g.put(r0, COL.L, COL.L + 1, "", `<span class="tlet" style="color:${tx(code)}">${letter}</span>`);
      g.put(r0, COL.TN, COL.TN + 1, "avg pl", status);
      if (target) g.put(r0 + 1, COL.L, COL.FL, "mark tgt", "Target");
    } else if (b.kind === "party") {
      g.row("P");
      g.pinFrom();
      sectionLine(g, b.head, b.note, true);
      const own = TEAM[v.ownTeam];
      let r0 = 0;
      b.rows.forEach((row, i) => {
        const r = playerRow(g, row, true);
        if (!i) r0 = r;
      });
      if (own && r0) {
        g.put(r0, COL.L, COL.L + 1, "", `<span class="tlet" style="color:${tx(own[1])}">${own[0]}</span>`);
        g.put(r0, COL.TN, COL.TN + 1, "mark you pl", "YOU");
      }
    }
  }
  return g.html("game");
}

function listHTML(v) {
  const g = new Grid();
  labels(g, false);
  let first = true;
  for (const b of v.blocks) {
    g.row(first ? 2 : "P");
    first = false;
    if (b.kind === "party" && v.blocks.length > 1) g.pinFrom();
    if (b.rows.length || b.kind === "party") sectionLine(g, b.head, b.note, false);
    b.rows.forEach((row) => playerRow(g, row, false));
  }
  return g.html("list");
}

/** The roster grid's markup for a view. */
export const rosterHTML = (v) => (v.context === "GAME" ? gameHTML(v) : listHTML(v));

/** A centred message: 2× title, one §7 line. */
export function messageHTML(title, sub = "") {
  return `<div class="msg"><div class="mt mc">${esc(title)}</div>${sub ? `<div class="s mc">${subHTML(sub)}</div>` : ""}</div>`;
}

/** The header's view name: the title in bold §e, the sub in §7 without its leading `· `. */
export function headerHTML(v) {
  const sub = String(v.sub ?? "").replace(/^\s*·\s*/, "");
  return `<span class="t">${esc(v.title)}</span><span class="u">${esc(sub)}</span>`;
}

const REF_GAME = ["gameSolo", "gameDoubles", "gameThrees", "gameFours", "game4v4"];
const REF_LIST = ["lobby", "queueSolo", "queueDoubles", "queueThrees", "queueFours"];

/**
 * @param {{ stage: HTMLElement, board: HTMLElement, title: HTMLElement, joining: HTMLElement, win?: Window }} els
 */
export function createBoard({ stage, board, title, joining, win = window }) {
  const doc = stage.ownerDocument;
  let REF = null;
  let REF_FONT = null;
  let current = null; // the view on the board: a roster view or {note}
  let last = null; // the last layout, for the preview and tests

  const dpr = () => win.devicePixelRatio || 1;
  const su = () => parseFloat(win.getComputedStyle(doc.documentElement).getPropertyValue("--su")) || 2;
  const frameNow = () => frame(win.innerWidth, win.innerHeight, su());
  const font = () => doc.documentElement.dataset.font || "minecraft";

  /** Width and height in GUI px, at the tight spacing unless `vars`; `core` leaves the notes out. */
  function measure(html, narrow = "", vars = ZERO) {
    const m = doc.createElement("div");
    m.className = "bmeasure";
    m.style.cssText = "position:absolute;left:-99999px;top:0;width:max-content;--bp:10px;visibility:hidden;" + FILLVARS.map((k) => `--${k}:${vars[k] || 0}`).join(";");
    m.innerHTML = `<div class="panel">${html}</div>`;
    const sb = m.querySelector(".sb");
    if (narrow) sb.className += " " + narrow;
    stage.appendChild(m);
    const nt = m.querySelectorAll(".nt");
    nt.forEach((n) => (n.style.display = "none"));
    const core = m.offsetWidth / 10;
    nt.forEach((n) => (n.style.display = ""));
    const full = m.offsetWidth / 10;
    const h = m.offsetHeight / 10;
    const L = Number(sb.dataset.l);
    const G = Number(sb.dataset.g);
    m.remove();
    return { core, full, notes: full - core, h, L, G };
  }

  /** The reference widths and the tallest game, from the fixtures the board was approved against, per font. */
  function refs() {
    if (REF && REF_FONT === font()) return REF;
    const R = { GAME: { core: 0, notes: 0, h: 0 }, LIST: { core: 0, notes: 0 } };
    for (const [kind, keys] of [["GAME", REF_GAME], ["LIST", REF_LIST]]) {
      for (const key of keys) {
        const r = measure(rosterHTML(boardView(PREVIEW_LOBBY[key])));
        R[kind].core = Math.max(R[kind].core, r.core);
        R[kind].notes = Math.max(R[kind].notes, r.notes);
        if (kind === "GAME") R.GAME.h = Math.max(R.GAME.h, r.h);
      }
    }
    // a hidden stage measures nothing: keep asking until it is shown
    if (R.GAME.core > 0) {
      REF = R;
      REF_FONT = font();
    }
    return R;
  }

  const kGame = () => {
    const R = refs();
    return kFor(R.GAME.core, R.GAME.h, frameNow(), dpr());
  };

  function setScale(el, k) {
    el.style.setProperty("--bp", k / dpr() + "px");
    el.style.setProperty("--nudge", (k % 2 ? -0.5 / dpr() : 0) + "px");
  }
  function setVars(vars) {
    for (const k of FILLVARS) board.style.setProperty("--" + k, String(vars[k] || 0));
  }

  function placeMsg(host, offsetTop) {
    const msg = host.querySelector(".msg");
    if (!msg) return;
    const s = su();
    const y = Math.round(win.innerHeight / 2 - offsetTop - msg.offsetHeight / 2);
    msg.style.top = Math.max(2 * s, y) + "px";
  }

  /** Whole items leave the notes from the end, then `…` says there is more (the cell's hover title has them all). */
  function trimNotes() {
    board.querySelectorAll(".nt").forEach((n) => {
      n.querySelectorAll(".more").forEach((m) => m.remove());
      const kids = Array.from(n.children);
      kids.forEach((k) => k.classList.remove("hide"));
      if (!kids.length) return;
      const sb = n.closest(".sb");
      const pad = parseFloat(win.getComputedStyle(sb).paddingRight) || 0;
      const lim = Math.min(n.getBoundingClientRect().right, sb.getBoundingClientRect().right - pad, board.getBoundingClientRect().right - pad);
      if (kids[kids.length - 1].getBoundingClientRect().right <= lim + 0.5) return;
      const more = doc.createElement("span");
      more.className = "more";
      more.innerHTML = glyphs("…");
      n.appendChild(more);
      let shown = kids.length;
      for (let i = kids.length - 1; i >= 0; i--) {
        kids[i].classList.add("hide");
        shown = i;
        if (i > 0 && kids[i - 1].classList.contains("sep")) {
          kids[i - 1].classList.add("hide");
          shown = i - 1;
        }
        if (more.getBoundingClientRect().right <= lim + 0.5) break;
      }
      if (!shown && more.getBoundingClientRect().right > lim + 0.5) more.remove();
    });
  }

  function place(m, bp, aw, vars) {
    const f = frameNow();
    const d = dpr();
    const w = Math.min(aw, snapPx(m.full * bp, d));
    const ph = snapPx(m.h * bp, d);
    const box = boardBox(f, w, ph, bp, d);
    board.style.left = box.x + "px";
    board.style.top = f.top + box.y + "px";
    board.style.width = w + "px";
    board.style.height = "";
    board.style.maxHeight = box.maxH + "px";
    board.style.overflowY = box.over ? "auto" : "hidden";
    if (box.over) {
      // a scrolling board ends on a whole row above the pinned party: no sliced row at the edge
      const pitch = (9 + (vars.e || 0)) * bp;
      const pinned = board.querySelectorAll(".pinbg").length;
      const foot = pinned ? pinned * pitch + (6 + (vars.g || 0)) * bp : 0;
      const top0 = board.getBoundingClientRect().top;
      let best = 0;
      board.querySelectorAll(".pr:not(.pin)").forEach((e) => {
        const b = e.getBoundingClientRect().top - top0 + pitch;
        if (b + foot <= box.maxH + 0.5) best = Math.max(best, b);
      });
      if (best) board.style.height = snapPx(best + foot, d) + "px";
    }
    return { w, ph, ...box };
  }

  function drawRoster(v) {
    const html = rosterHTML(v);
    const R = refs();
    const f = frameNow();
    const d = dpr();
    const base = measure(html);
    const game = v.context === "GAME";
    const spec = game ? { game, h: base.h, L: base.L, G: base.G } : { game, ...reserveSpec(base, v.context, rowCount(v)) };
    // one k per window for every context; a roster wider than the reference steps it down rather than clip
    const k = kForWidth(kFor(R.GAME.core, R.GAME.h, f, d), base.core, f, d);
    const ref = game ? R.GAME : R.LIST;
    const lay = fill(spec, { core: Math.max(ref.core, base.core), notes: ref.notes }, k, f, d);
    const bp = k / d;
    let narrow = "";
    let m = measure(html, "", lay.vars);
    // at the floor scale a board too wide first gives up the side margins, then Finals and K/D, then WLR
    let aw = f.aw;
    if (k === floorK(d) && m.core * bp > aw) {
      aw = f.awMin;
      if (m.core * bp > aw) {
        narrow = "n1";
        m = measure(html, narrow, lay.vars);
        if (m.core * bp > aw) {
          narrow = "n1 n2";
          m = measure(html, narrow, lay.vars);
        }
      }
    }
    setVars(lay.vars);
    setScale(board, k);
    board.dataset.mode = "roster";
    board.innerHTML = `<div class="panel">${html}</div>`;
    if (narrow) board.querySelector(".sb").className += " " + narrow;
    const p = place(m, bp, aw, lay.vars);
    trimNotes();
    title.innerHTML = headerHTML(v);
    last = { k, vars: lay.vars, narrow, over: p.over };
  }

  function drawNote(text) {
    const f = frameNow();
    setVars(ZERO);
    setScale(board, kGame());
    board.dataset.mode = "note";
    board.innerHTML = messageHTML(text);
    Object.assign(board.style, { left: "0px", top: f.top + "px", width: f.W + "px", height: f.H - f.top + "px", maxHeight: "", overflowY: "hidden" });
    placeMsg(board, f.top);
    title.innerHTML = "";
    last = null;
  }

  /** Draws a view: a roster (board-model.js) or a bare dashboard-view.js note. Throws if it cannot. */
  function draw(view) {
    current = view;
    if (view.bare) drawNote(view.note ?? "");
    else drawRoster(view);
  }

  /** The window, its scale or the font changed: lay the same view out again. */
  function relayout() {
    if (current && board.dataset.mode) draw(current);
    placeScreen();
  }

  /** A roster render failed: the note, centred in the board's area. */
  function fail(message) {
    const f = frameNow();
    current = null;
    last = null;
    title.innerHTML = "";
    delete board.dataset.mode;
    Object.assign(board.style, { left: "0px", top: f.top + "px", width: f.W + "px", height: f.H - f.top + "px", maxHeight: "", overflowY: "auto" });
    const note = doc.createElement("div");
    note.className = "dash-error";
    note.textContent = message;
    board.replaceChildren(note);
  }

  function clear() {
    current = null;
    last = null;
    title.innerHTML = "";
    delete board.dataset.mode;
    board.replaceChildren();
  }

  /** The connection screens use the scale the board would use in this window, so nothing jumps between states. */
  function placeScreen() {
    if (!joining.classList.contains("on")) return;
    setScale(joining, kGame());
    placeMsg(joining, frameNow().top);
  }

  return { draw, relayout, fail, clear, placeScreen, layout: () => last, invalidate: () => (REF = null) };
}
