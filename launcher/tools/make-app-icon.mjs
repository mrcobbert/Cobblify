#!/usr/bin/env node
// Draws the launcher's app icon, a pocket Bed Wars island with a blue bed, and writes every icon file Tauri bundles:
//   src-tauri/icons/Cobblify.icon   Icon Composer file: light and dark tiles for macOS 26+ (compiled by actool 26 at build)
//   src-tauri/icons/icon.icns       older macOS: the dark tile on Apple's 1024 grid (824 body, drop shadow)
//   src-tauri/icons/icon.ico        Windows: the dark tile, edge to edge
//   src-tauri/icons/icon.png        512 px copy of the macOS tile (dev runs and the default window icon)
// The island is pixel art: the launcher island's 2:1 view and face shading (top 98%, left 80%, right 60.8%), rasterized
// at 3 art px per block and scaled by whole pixels wherever a size allows it. macOS only (iconutil builds the .icns).
//   node tools/make-app-icon.mjs [--preview <dir>]
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import zlib from "node:zlib";

const ICONS = path.join(path.dirname(fileURLToPath(import.meta.url)), "../src-tauri/icons");
const TILE = {
  dark: ["#1a1f19", "#101310"],
  light: ["#f6f8f4", "#e2e7df"],
};
const OUTLINE = "#141613";
const SPAN = 0.7; // the island's share of the tile, as in the options page

// ---- the island ----
const MAT = {
  grass: "#91bd59", grassSide: "#7aa94a", dirt: "#866043", stone: "#7f7f7f", deep: "#5e5e5e",
  wood: "#9c7a48", pillow: "#e8e8e8", blue: "#3c44aa",
};
const TEXTURED = { grass: 1, grassSide: 1, dirt: 1, stone: 1, deep: 1, wood: 0.5 };
const hex = (h) => { const n = parseInt(h.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const hash2 = (x, y, s) => { const h = Math.sin(x * 127.1 + y * 311.7 + s * 74.7) * 43758.5453; return h - Math.floor(h); };

function floating(rows, layers) {
  let cur = [];
  rows.forEach((r, z) => { for (let x = 0; x < r.length; x++) if (r[x] === "#") cur.push([x, z]); });
  const out = [];
  for (let y = 0; y > -layers; y--) {
    for (const [x, z] of cur) out.push({ x, y, z, m: y === 0 ? "grass" : y === -1 ? "dirt" : y === -2 ? "stone" : "deep" });
    const set = new Set(cur.map(([x, z]) => `${x},${z}`));
    const keep = cur.filter(([x, z]) => {
      const n = [[1, 0], [-1, 0], [0, 1], [0, -1]].filter(([dx, dz]) => set.has(`${x + dx},${z + dz}`)).length;
      return y === 0 || n >= 3 || (n === 2 && hash2(x, z, y) > 0.45); // dirt sits under all the grass
    });
    if (!keep.length) break;
    cur = keep;
  }
  return out;
}

// two blocks long along +x: wood frame, blue blanket, white pillow at the head
const bed = (x, y, z) => [
  { box: [x, y, z, x + 2, y + 0.25, z + 1], m: "wood" },
  { box: [x, y + 0.25, z, x + 1.45, y + 0.62, z + 1], m: "blue" },
  { box: [x + 1.45, y + 0.25, z, x + 2, y + 0.62, z + 1], m: "pillow" },
];

const ISLAND = floating([".###.", "#####", "#####", "#####", ".###."], 4).concat(bed(1, 1, 2));

/** Rasterizes blocks and boxes to RGBA pixel art, painter's order along the view axis, with a 1 px outline. */
function raster(items, a) {
  const occ = new Set(items.filter((i) => !i.box).map((i) => `${i.x},${i.y},${i.z}`));
  const P = (x, y, z) => [(x - z) * a, ((x + z) * a) / 2 - y * a];
  const faces = [];
  for (const it of items) {
    const b = it.box ?? [it.x, it.y, it.z, it.x + 1, it.y + 1, it.z + 1];
    const [x0, y0, z0, x1, y1, z1] = b;
    const d = (x0 + x1 + y0 + y1 + z0 + z1) / 2 + (it.box ? 0.01 : 0);
    const hid = (dx, dy, dz) => !it.box && occ.has(`${it.x + dx},${it.y + dy},${it.z + dz}`);
    const grassTop = it.m === "grass" && !hid(0, 1, 0);
    const hgt = (y1 - y0) * a;
    if (!hid(0, 1, 0)) faces.push({ d, m: it.m, k: "top", p: [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)] });
    if (!hid(0, 0, 1)) faces.push({ d, m: it.m, k: "left", p: [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], top: [P(x0, y1, z1), P(x1, y1, z1)], hgt, grassTop });
    if (!hid(1, 0, 0)) faces.push({ d, m: it.m, k: "right", p: [P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1), P(x1, y0, z1)], top: [P(x1, y1, z0), P(x1, y1, z1)], hgt, grassTop });
  }
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const f of faces) for (const [x, y] of f.p) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
  const ox = Math.floor(minX) - 1, oy = Math.floor(minY) - 1;
  const w = Math.ceil(maxX) - ox + 1, h = Math.ceil(maxY) - oy + 1, px = new Uint8ClampedArray(w * h * 4);
  const SHADE = { top: 0.98, left: 0.8, right: 0.608 };
  faces.sort((f, g) => f.d - g.d);
  for (const f of faces) {
    const q = f.p.map(([x, y]) => [x - ox, y - oy]);
    const xs = q.map((v) => v[0]), ys = q.map((v) => v[1]);
    let sgn = 0;
    for (let i = 0; i < 4 && !sgn; i++) {
      const [A, B, C] = [q[i], q[(i + 1) % 4], q[(i + 2) % 4]];
      const cr = (B[0] - A[0]) * (C[1] - A[1]) - (B[1] - A[1]) * (C[0] - A[0]);
      if (cr) sgn = Math.sign(cr);
    }
    if (!sgn) continue;
    for (let y = Math.floor(Math.min(...ys)); y < Math.ceil(Math.max(...ys)); y++) {
      for (let x = Math.floor(Math.min(...xs)); x < Math.ceil(Math.max(...xs)); x++) {
        const cx = x + 0.5, cy = y + 0.5;
        let inside = true;
        for (let e = 0; e < 4 && inside; e++) {
          const [a0, a1] = [q[e], q[(e + 1) % 4]];
          if (((a1[0] - a0[0]) * (cy - a0[1]) - (a1[1] - a0[1]) * (cx - a0[0])) * sgn < -0.35) inside = false;
        }
        if (!inside || x < 0 || y < 0 || x >= w || y >= h) continue;
        let m = f.m;
        if (f.grassTop && f.k !== "top") {
          const [t0, t1] = f.top, ex = t1[0] - t0[0];
          const edgeY = (ex ? t0[1] + (t1[1] - t0[1]) * ((cx + ox - t0[0]) / ex) : t0[1]) - oy;
          m = cy - edgeY < f.hgt * (0.3 + hash2(x, 0, 3) * 0.2) ? "grassSide" : "dirt";
        }
        const c = hex(MAT[m]);
        let s = SHADE[f.k];
        if (TEXTURED[m]) s *= 1 + (hash2(x, y, 1) - 0.5) * 0.16 * TEXTURED[m];
        const o = (y * w + x) * 4;
        px[o] = c[0] * s; px[o + 1] = c[1] * s; px[o + 2] = c[2] * s; px[o + 3] = 255;
      }
    }
  }
  const oc = hex(OUTLINE), src = new Uint8ClampedArray(px);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const k = (y * w + x) * 4;
    if (src[k + 3]) continue;
    const near = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => {
      const nx = x + dx, ny = y + dy;
      return nx >= 0 && ny >= 0 && nx < w && ny < h && src[(ny * w + nx) * 4 + 3];
    });
    if (near) { px[k] = oc[0]; px[k + 1] = oc[1]; px[k + 2] = oc[2]; px[k + 3] = 200; }
  }
  return { w, h, px };
}

const ART = raster(ISLAND, 3);

// ---- images ----
const image = (w, h) => ({ w, h, px: new Float32Array(w * h * 4) }); // premultiplied-free RGBA, 0..255

function over(img, x, y, r, g, b, a) {
  if (x < 0 || y < 0 || x >= img.w || y >= img.h || a <= 0) return;
  const o = (y * img.w + x) * 4, A = a / 255, da = img.px[o + 3] / 255, oa = A + da * (1 - A);
  if (oa <= 0) return;
  img.px[o] = (r * A + img.px[o] * da * (1 - A)) / oa;
  img.px[o + 1] = (g * A + img.px[o + 1] * da * (1 - A)) / oa;
  img.px[o + 2] = (b * A + img.px[o + 2] * da * (1 - A)) / oa;
  img.px[o + 3] = oa * 255;
}

/** Coverage of a rounded square (x0, y0, size, radius) at pixel (x, y), 4x4 supersampled. */
function roundCoverage(x, y, x0, y0, s, r) {
  let hit = 0;
  for (let j = 0; j < 4; j++) for (let i = 0; i < 4; i++) {
    const px = x + (i + 0.5) / 4 - x0, py = y + (j + 0.5) / 4 - y0;
    if (px < 0 || py < 0 || px > s || py > s) continue;
    const qx = Math.min(Math.max(px, r), s - r), qy = Math.min(Math.max(py, r), s - r);
    if ((px - qx) ** 2 + (py - qy) ** 2 <= r * r) hit++;
  }
  return hit / 16;
}

/** The tile: a vertical gradient in a rounded square, optional soft drop shadow (Apple's grid). */
function tile(img, mode, x0, y0, s, shadow) {
  const [top, bottom] = TILE[mode].map(hex), r = s * 0.225;
  if (shadow) {
    const blur = Math.max(1, Math.round(s * 0.012)), dy = Math.round(s * 0.012);
    const mask = new Float32Array(img.w * img.h);
    for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) mask[y * img.w + x] = roundCoverage(x, y - dy, x0, y0, s, r);
    const blurred = boxBlur(mask, img.w, img.h, blur);
    for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) over(img, x, y, 0, 0, 0, blurred[y * img.w + x] * 0.3 * 255);
  }
  for (let y = Math.floor(y0); y < Math.ceil(y0 + s); y++) for (let x = Math.floor(x0); x < Math.ceil(x0 + s); x++) {
    const cov = roundCoverage(x, y, x0, y0, s, r);
    if (!cov) continue;
    const t = Math.min(1, Math.max(0, (y + 0.5 - y0) / s));
    over(img, x, y, top[0] + (bottom[0] - top[0]) * t, top[1] + (bottom[1] - top[1]) * t, top[2] + (bottom[2] - top[2]) * t, cov * 255);
  }
}

function boxBlur(src, w, h, r) {
  let a = src, b = new Float32Array(w * h);
  for (let pass = 0; pass < 3; pass++) {
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      let sum = 0, n = 0;
      for (let k = -r; k <= r; k++) { const xx = x + k; if (xx >= 0 && xx < w) { sum += a[y * w + xx]; n++; } }
      b[y * w + x] = sum / n;
    }
    [a, b] = [b, a];
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      let sum = 0, n = 0;
      for (let k = -r; k <= r; k++) { const yy = y + k; if (yy >= 0 && yy < h) { sum += a[yy * w + x]; n++; } }
      b[y * w + x] = sum / n;
    }
    [a, b] = [b, a];
  }
  return a;
}

/** The island at a whole-pixel scale k, centred on (cx, cy). */
function islandAt(img, k, cx, cy) {
  const ox = Math.round(cx - (ART.w * k) / 2), oy = Math.round(cy - (ART.h * k) / 2);
  for (let y = 0; y < ART.h * k; y++) for (let x = 0; x < ART.w * k; x++) {
    const s = (Math.floor(y / k) * ART.w + Math.floor(x / k)) * 4;
    over(img, ox + x, oy + y, ART.px[s], ART.px[s + 1], ART.px[s + 2], ART.px[s + 3]);
  }
}

/** Area-average downscale (for sizes too small for a whole-pixel island). */
function downscale(src, size) {
  const out = image(size, size), f = src.w / size;
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    const acc = [0, 0, 0, 0];
    let n = 0;
    for (let j = Math.floor(y * f); j < Math.floor((y + 1) * f); j++) for (let i = Math.floor(x * f); i < Math.floor((x + 1) * f); i++) {
      const o = (j * src.w + i) * 4, a = src.px[o + 3];
      acc[0] += src.px[o] * a; acc[1] += src.px[o + 1] * a; acc[2] += src.px[o + 2] * a; acc[3] += a; n++;
    }
    const o = (y * size + x) * 4;
    if (acc[3]) { out.px[o] = acc[0] / acc[3]; out.px[o + 1] = acc[1] / acc[3]; out.px[o + 2] = acc[2] / acc[3]; }
    out.px[o + 3] = acc[3] / n;
  }
  return out;
}

/** Whole-pixel island scale for a tile body, or 0 when the nearest one would crowd or shrink it. */
function wholeScale(body) {
  const side = Math.max(ART.w, ART.h), k = Math.round((SPAN * body) / side);
  return k >= 1 && k * side <= 0.85 * body && k * side >= 0.55 * body ? k : 0;
}

/** macOS (Apple grid, 824/1024 body with shadow) or Windows (edge to edge) tile at one size. */
function iconImage(size, kind, mode = "dark") {
  const grid = kind === "mac" ? 824 / 1024 : 1;
  const k = wholeScale(size * grid);
  if (!k && size < 1024) return downscale(iconImage(1024, kind, mode), size);
  const img = image(size, size), body = size * grid, x0 = (size - body) / 2;
  tile(img, mode, x0, x0 - (kind === "mac" ? size * 0.0049 : 0), body, kind === "mac");
  islandAt(img, k || wholeScale(body), size / 2, x0 - (kind === "mac" ? size * 0.0049 : 0) + body / 2);
  return img;
}

// ---- encoders ----
function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function png(img) {
  const raw = Buffer.alloc((img.w * 4 + 1) * img.h);
  for (let y = 0; y < img.h; y++) {
    raw[y * (img.w * 4 + 1)] = 0;
    for (let x = 0; x < img.w * 4; x++) raw[y * (img.w * 4 + 1) + 1 + x] = Math.round(Math.min(255, Math.max(0, img.px[y * img.w * 4 + x])));
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4), crc = Buffer.alloc(4), td = Buffer.concat([Buffer.from(type), data]);
    len.writeUInt32BE(data.length); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(img.w, 0); ihdr.writeUInt32BE(img.h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]);
}
/** ICO with PNG entries (Windows Vista and later read them at every size). */
function ico(images) {
  const head = Buffer.alloc(6 + 16 * images.length), bodies = images.map(png);
  head.writeUInt16LE(0, 0); head.writeUInt16LE(1, 2); head.writeUInt16LE(images.length, 4);
  let off = head.length;
  images.forEach((img, i) => {
    const e = 6 + i * 16;
    head[e] = img.w >= 256 ? 0 : img.w; head[e + 1] = img.h >= 256 ? 0 : img.h;
    head.writeUInt16LE(1, e + 4); head.writeUInt16LE(32, e + 6);
    head.writeUInt32LE(bodies[i].length, e + 8); head.writeUInt32LE(off, e + 12);
    off += bodies[i].length;
  });
  return Buffer.concat([head, ...bodies]);
}

const hexToSrgb = (h) => `srgb:${hex(h).map((v) => (v / 255).toFixed(5)).join(",")},1.00000`;

// ---- write ----
const preview = process.argv.includes("--preview") ? process.argv[process.argv.indexOf("--preview") + 1] : null;

// Icon Composer: the system draws the rounded tile (the fill), the island is one flat layer with no glass.
const iconDir = path.join(ICONS, "Cobblify.icon");
fs.rmSync(iconDir, { recursive: true, force: true });
fs.mkdirSync(path.join(iconDir, "Assets"), { recursive: true });
const layer = image(1024, 1024);
islandAt(layer, wholeScale(1024), 512, 512);
fs.writeFileSync(path.join(iconDir, "Assets", "island.png"), png(layer));
const iconJson = {
  "fill-specializations": [
    { value: { "linear-gradient": TILE.light.map(hexToSrgb) } },
    { appearance: "dark", value: { "linear-gradient": TILE.dark.map(hexToSrgb) } },
  ],
  groups: [{
    layers: [{ "blend-mode": "normal", glass: false, hidden: false, "image-name": "island.png", name: "island", position: { scale: 1, "translation-in-points": [0, 0] } }],
    shadow: { kind: "none", opacity: 0.5 },
    translucency: { enabled: false, value: 0.5 },
    specular: false,
  }],
  "supported-platforms": { squares: ["macOS"] },
};
fs.writeFileSync(path.join(iconDir, "icon.json"), JSON.stringify(iconJson, null, 2) + "\n");

// .icns through iconutil
const iconset = fs.mkdtempSync(path.join(os.tmpdir(), "cobblify-icon-")) + "/icon.iconset";
fs.mkdirSync(iconset);
for (const s of [16, 32, 128, 256, 512]) {
  fs.writeFileSync(path.join(iconset, `icon_${s}x${s}.png`), png(iconImage(s, "mac")));
  fs.writeFileSync(path.join(iconset, `icon_${s}x${s}@2x.png`), png(iconImage(s * 2, "mac")));
}
execFileSync("iconutil", ["-c", "icns", iconset, "-o", path.join(ICONS, "icon.icns")]);
fs.rmSync(path.dirname(iconset), { recursive: true, force: true });

fs.writeFileSync(path.join(ICONS, "icon.ico"), ico([16, 24, 32, 48, 64, 128, 256].map((s) => iconImage(s, "win"))));
fs.writeFileSync(path.join(ICONS, "icon.png"), png(iconImage(512, "mac")));

if (preview) {
  fs.mkdirSync(preview, { recursive: true });
  for (const mode of ["dark", "light"]) {
    const t = image(1024, 1024);
    tile(t, mode, 0, 0, 1024, false);
    islandAt(t, wholeScale(1024), 512, 512);
    fs.writeFileSync(path.join(preview, `tahoe-${mode}.png`), png(t));
  }
  for (const s of [16, 32, 64, 128]) fs.writeFileSync(path.join(preview, `mac-${s}.png`), png(iconImage(s, "mac")));
  for (const s of [16, 24, 32, 48, 256]) fs.writeFileSync(path.join(preview, `win-${s}.png`), png(iconImage(s, "win")));
}
console.log(`island art ${ART.w}x${ART.h}; wrote Cobblify.icon, icon.icns, icon.ico, icon.png to ${ICONS}`);
