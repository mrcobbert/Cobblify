/**
 * Pixel art and text helpers from the 0.18 prototype (proto/servers.js): 1 art px = 1 su, on the same grid as
 * every bevel. Rows use '#' for the first colour and '+' for the second.
 */

export const esc = (s) =>
  String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

/** Text in the UI font: a span whose first glyph nudge() can put on a device pixel. */
export const T = (s, cls = "") => `<span class="t${cls ? " " + cls : ""}">${esc(s)}</span>`;

/** Minecraft's text shadow colour: the colour at a quarter. */
export const quarter = (hex) => "#" + ((parseInt(hex.slice(1), 16) & 0xfcfcfc) >> 2).toString(16).padStart(6, "0");

function runs(rows) {
  const w = rows[0].length, paths = { "#": "", "+": "" };
  rows.forEach((r, y) => {
    let x = 0;
    while (x < w) {
      const ch = r[x];
      if (ch === "#" || ch === "+") {
        let e = x;
        while (e < w && r[e] === ch) e++;
        paths[ch] += `M${x} ${y}h${e - x}v1h${x - e}z`;
        x = e;
      } else x++;
    }
  });
  return paths;
}

export function pix(rows, color, unit = 1, color2) {
  const h = rows.length, w = rows[0].length, p = runs(rows);
  return `<svg viewBox="0 0 ${w} ${h}" style="width:calc(${w * unit}*var(--su));height:calc(${h * unit}*var(--su))" shape-rendering="crispEdges" aria-hidden="true"><path fill="${color}" d="${p["#"]}"/>${p["+"] ? `<path fill="${color2 || color}" d="${p["+"]}"/>` : ""}</svg>`;
}

/**
 * pix() plus Minecraft's text shadow: each colour's pixels again 1 art px down-right in its shadow colour, under
 * the art, the way SheetPainter shadows its pixel chevron beside shadowed text. The shadow hangs outside the box,
 * as a glyph's does, so the art still centres on its own pixels.
 */
export function pixSh(rows, c1, c2, s1, s2) {
  const h = rows.length, w = rows[0].length, p = runs(rows);
  const sh = (d, c) => (d && c ? `<path fill="${c}" transform="translate(1 1)" d="${d}"/>` : "");
  return `<svg viewBox="0 0 ${w} ${h}" style="width:calc(${w}*var(--su));height:calc(${h}*var(--su));overflow:visible" shape-rendering="crispEdges" aria-hidden="true">${sh(p["#"], s1)}${sh(p["+"], s2 || s1)}<path fill="${c1}" d="${p["#"]}"/>${p["+"] ? `<path fill="${c2 || c1}" d="${p["+"]}"/>` : ""}</svg>`;
}

/** The art cut to its own pixels, so the gap to a label is measured from the moon's tips, as from the prism's base. */
export const trimArt = (rows) => {
  const used = (x) => rows.some((r) => r[x] !== ".");
  let a = 0, b = rows[0].length - 1;
  while (a < b && !used(a)) a++;
  while (b > a && !used(b)) b--;
  return rows.map((r) => r.slice(a, b + 1));
};

export const ART = {
  moon: ["...###....", ".####.....", ".###......", "###.......", "###.......", "###.......", "###.......", ".###......", ".####.....", "...###...."],
  /* a solid prism, lit face left, shaded face right, so it can't read as a warning triangle */
  prism: ["....#+....", "....#+....", "...##++...", "...##++...", "..###+++..", "..###+++..", ".####++++.", ".####++++.", "#####+++++", "#####+++++"],
  plus: ["..##..", "..##..", "######", "######", "..##..", "..##.."],
  /* 8 su so it centres in the 16 su close key, the gear key's twin */
  x: ["##....##", "###..###", ".######.", "..####..", "..####..", ".######.", "###..###", "##....##"],
  back: ["...##", "..##.", ".##..", "##...", ".##..", "..##.", "...##"],
  check: [".......##", "......##.", "##...##..", ".##.##...", "..###....", "...#....."],
  /* 10 su, an even grid: eight identical flat 2 px teeth and every gap 2 px wide, round an octagonal hole */
  gear: ["..##..##..", "..######..", "##########", "####..####", ".##....##.", ".##....##.", "####..####", "##########", "..######..", "..##..##.."],
  caret: ["#####", ".###.", "..#.."],
  /* a client that isn't installed: picking it opens its download page */
  dl: ["..###..", "..###..", "..###..", "#######", ".#####.", "..###..", "...#...", ".......", "#######"],
};

/**
 * The clients, by UI id. The backend calls Prism's target "forge". `pick` is the name in Choose Your Setup's menu,
 * which says what Cobblify supports (Forge, through Prism).
 */
export const CL = {
  lunar: { name: "Lunar Client", short: "Lunar", pick: "Lunar Client", kind: "lunar", art: ART.moon },
  prism: { name: "Prism Launcher", short: "Prism", pick: "Forge (Prism)", kind: "forge", art: ART.prism },
};
export const uiId = (kind) => (kind === "forge" ? "prism" : kind);
