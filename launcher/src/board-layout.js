// The stats board's layout rule ("Fill", the user's pick 2026-10-11), as pure arithmetic. board-render.js measures
// the board at one GUI px = 1 and hands the numbers here; nothing in this file touches the DOM.
//
// 1. Scale. k = the largest whole number of device px per GUI px at which the worst game (the reference roster,
//    Doubles: 16 players, 8 teams) fits one column at the sidebar's tight spacing; width counts the core (gutter to
//    K/D) plus room for a presence word, not the full notes (they end in "…"). Floor: GUI scale 2. One k per window
//    for lobby, queue and game.
// 2. Fill. What is left goes to whole GUI px: height to the row pitch (9 → at most 13) and the team gaps, width to
//    the gaps between columns within caps. A list is laid out for a fixed reserve of rows and scrolls past it.
// Decided per window size and context, never on a data tick.

/** GUI px a presence word needs with its gap ("GONE", "OUT"). */
export const NOTES_MIN = 36;
/** Fill caps: pitch +4, team gaps +2 past the pitch, 6 rounds of column gaps, name→FKDR +4, the gutter +2. */
export const LIM = { e: 4, gPlus: 2, rounds: 6, gf: 4, gg: 2 };
/** Rows a list is laid out for: a lobby party 4 + 12 chatters; a queue's fullest case (16 known + party 4). */
export const RESERVE = { LOBBY: 16, QUEUE: 20 };
/** The fill's CSS variables, all whole GUI px: pitch, team gap, stat gaps, name→FKDR, K/D→notes, gutter. */
export const FILLVARS = ["e", "g", "gs", "gf", "gn", "gg"];
export const ZERO = Object.freeze({ e: 0, g: 0, gs: 0, gf: 0, gn: 0, gg: 0 });

/**
 * The window in CSS px: header band `hdr` su (24; 36 on macOS outside full screen), 5 su margins; `aw`/`ah` the
 * board's room, `awMin` the room once a board at the floor scale gives up its side margins.
 */
export function frame(W, H, su, hdr = 24) {
  return { W, H, su, top: hdr * su, m: 5 * su, aw: W - 15 * su, awMin: W - 8 * su, ah: H - hdr * su - 10 * su };
}

/** GUI scale 2 in device px. */
export const floorK = (dpr) => Math.max(1, Math.floor(2 * (dpr || 1)));

/** The largest whole k (device px per GUI px) at which `core` + a presence word and `h` (GUI px) fit the frame. */
export function kFor(core, h, f, dpr) {
  const d = dpr || 1;
  for (let t = Math.floor(12 * d); t > floorK(d); t--) {
    if (((core + NOTES_MIN) * t) / d <= f.aw && (h * t) / d <= f.ah) return t;
  }
  return floorK(d);
}

/** k lowered, never below the floor, until a measured core (GUI px) fits the width: a roster wider than the reference. */
export function kForWidth(k, core, f, dpr) {
  const d = dpr || 1;
  let t = k;
  while (t > floorK(d) && (core * t) / d > f.aw) t--;
  return t;
}

/** A list's height spec for its reserve rows, not its live count: a chatter arriving never re-spaces the board. */
export function reserveSpec(base, context, rows) {
  const extra = RESERVE[context] != null ? RESERVE[context] - rows : 0;
  return { h: base.h + 9 * extra, L: base.L + extra, G: base.G };
}

/**
 * The fill at scale k. spec = {game, h, L, G}: h the height at the tight spacing, L its text lines, G its gaps;
 * ref = {core, notes} the reference widths for the context's kind. Returns {vars, W, H} (room in GUI px).
 */
export function fill(spec, ref, k, f, dpr) {
  const bp = k / (dpr || 1);
  const H = Math.floor(f.ah / bp + 1e-6);
  const W = Math.floor(f.aw / bp + 1e-6);
  const tall = (e, g) => spec.h + spec.L * e + spec.G * g;
  let e = LIM.e;
  while (e > 0 && tall(e, e) > H) e--;
  let g = e;
  while (g < e + LIM.gPlus && tall(e, g + 1) <= H) g++;
  const vars = { e, g, gs: 0, gf: 0, gn: 0, gg: 0 };
  let left = W - ref.core - Math.max(NOTES_MIN, Math.ceil(ref.notes));
  for (let r = 0; r < LIM.rounds; r++) {
    const cost = 3 + 1 + (vars.gf < LIM.gf ? 1 : 0) + (spec.game && vars.gg < LIM.gg ? 2 : 0);
    if (left < cost) break;
    vars.gs++;
    vars.gn++;
    if (vars.gf < LIM.gf) vars.gf++;
    if (spec.game && vars.gg < LIM.gg) vars.gg++;
    left -= cost;
  }
  return { vars, W, H };
}

/** `x` CSS px on whole device px. */
export const snapPx = (x, dpr) => Math.round(x * (dpr || 1)) / (dpr || 1);

/**
 * Where the board sits under the header (CSS px from the stage's top-left, header excluded): its text starts on the
 * wordmark's 11 su line (the panel pads 2 GUI px), top-left, 5 su under the header. A board too wide slides left; the
 * bottom margin gives way down to 1 su before a board scrolls. `w`/`ph` are its width and full height in CSS px.
 */
export function boardBox(f, w, ph, bp, dpr) {
  const x = snapPx(Math.max(0, Math.min(11 * f.su - 2 * bp, f.W - w)), dpr);
  const areaH = f.H - f.top;
  const y = snapPx(f.m, dpr);
  const maxH = areaH - y - (ph > areaH - f.m - y + 0.5 ? f.su : f.m);
  return { x, y, maxH, over: ph > maxH + 0.5 };
}
