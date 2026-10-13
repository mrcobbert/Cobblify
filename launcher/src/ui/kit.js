/**
 * The Ore UI kit, ported from the 0.18 prototype (proto/kit/kit.js): the in-game OKLab shading, the accent and
 * font switches, whole-device-pixel units, and the in-game wordmark. The prototype's mock window frame and control
 * bar are not part of the app.
 */

/* ---- OKLab shading, verbatim from the approved in-game prototype ---- */
function lin(c) {
  c /= 255;
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}
function unlin(x) {
  x = Math.min(1, Math.max(0, x));
  return Math.round(255 * (x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055));
}
function toLab(hex) {
  const n = parseInt(hex.slice(1), 16), r = lin((n >> 16) & 255), g = lin((n >> 8) & 255), b = lin(n & 255);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b),
    m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b),
    s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}
function fromLab(L, a, b) {
  const l = Math.pow(L + 0.3963377774 * a + 0.2158037573 * b, 3),
    m = Math.pow(L - 0.1055613458 * a - 0.0638541728 * b, 3),
    s = Math.pow(L - 0.0894841775 * a - 1.291485548 * b, 3);
  const rgb = [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ];
  return "#" + rgb.map((x) => unlin(x).toString(16).padStart(2, "0")).join("");
}

export function shade(hex, dL, cm = 1) {
  const [L, a, b] = toLab(hex);
  return fromLab(Math.min(0.985, Math.max(0.05, L + dL)), a * cm, b * cm);
}

export function shades(hex) {
  return {
    hl: shade(hex, 0.1, 0.9),
    ln: shade(hex, 0.04),
    dp: shade(hex, -0.32, 0.8),
    rim: shade(hex, 0.14, 0.85),
    sh: shade(hex, -0.2, 0.85),
    sh2: shade(hex, -0.11, 0.92),
  };
}

function luminance(hex) {
  const n = parseInt(hex.slice(1), 16);
  return 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
}

export function contrast(a, b) {
  const x = luminance(a), y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

/** The accent custom properties for `hex`, including the ink that reads on it. */
export function accentVars(hex) {
  const s = shades(hex);
  const on = contrast(hex, "#121212") >= contrast(hex, "#EDEDED") ? "#121212" : "#EDEDED";
  return {
    "--acc": hex, "--acc-hl": s.hl, "--acc-ln": s.ln, "--acc-dp": s.dp,
    "--acc-rim": s.rim, "--acc-sh": s.sh, "--acc-sh2": s.sh2, "--acc-on": on,
  };
}

/**
 * The Launch key's colour: always the game's White accent (accent.js ACCENTS.white), whatever accent is set (TEST-NOTES 3,
 * the user 2026-10-11). Its shades are `--key*`, beside the accent's `--acc*`; app.css points the hero keys, their
 * progress fill, the menu's tick and the gear's dot at them. The wool and the settings keep the accent.
 */
export const KEY_WHITE = "#E6E6E6";
export function keyVars() {
  return Object.fromEntries(Object.entries(accentVars(KEY_WHITE)).map(([k, v]) => ["--key" + k.slice(5), v]));
}

export function setAccent(hex, root = document.documentElement) {
  const vars = { ...accentVars(hex), ...keyVars() };
  for (const k in vars) root.style.setProperty(k, vars[k]);
  root.dispatchEvent(new root.ownerDocument.defaultView.CustomEvent("cobblify:theme", { bubbles: true }));
}

export function setFont(font, root = document.documentElement) {
  root.dataset.font = font === "modern" ? "modern" : "minecraft";
  root.dispatchEvent(new root.ownerDocument.defaultView.CustomEvent("cobblify:theme", { bubbles: true }));
}

/* ---- whole-device-pixel units ---- */

/** CSS px for `cssTarget` rounded to whole device pixels (never below one device pixel). */
export function snap(cssTarget, dpr) {
  const d = dpr || 1;
  return Math.max(1, Math.round(cssTarget * d)) / d;
}

/** Sets --su (1 sheet unit, in-game GUI scale 2) and --fpx (1 Minecraft-font pixel) on whole device pixels. */
export function units(win = window) {
  const root = win.document.documentElement;
  const dpr = win.devicePixelRatio || 1;
  root.style.setProperty("--su", snap(2, dpr) + "px");
  root.style.setProperty("--fpx", snap(2, dpr) + "px");
  root.style.setProperty("--dpr", String(dpr));
  win.document.dispatchEvent(new win.CustomEvent("cobblify:units"));
}

/** Re-runs units() whenever the device pixel ratio changes (moving to another display, zoom). */
export function watchUnits(win = window) {
  units(win);
  if (!win.matchMedia) return;
  let mq;
  const watch = () => {
    mq = win.matchMedia(`(resolution: ${win.devicePixelRatio || 1}dppx)`);
    mq.addEventListener?.("change", () => {
      units(win);
      watch();
    }, { once: true });
  };
  watch();
}

/* ---- the in-game wordmark (SheetWordmark.ROWS, 63x10 art px, + Minecraft's 1 px quarter-brightness shadow) ---- */
const WM = "M0 0h6v1h-6zM8 0h7v1h-7zM17 0h6v1h-6zM26 0h6v1h-6zM35 0h3v1h-3zM43 0h3v1h-3zM48 0h6v1h-6zM56 0h3v1h-3zM60 0h3v1h-3zM0 1h6v1h-6zM8 1h7v1h-7zM17 1h7v1h-7zM26 1h7v1h-7zM35 1h3v1h-3zM43 1h3v1h-3zM48 1h6v1h-6zM56 1h3v1h-3zM60 1h3v1h-3zM0 2h3v1h-3zM8 2h3v1h-3zM12 2h3v1h-3zM17 2h3v1h-3zM21 2h3v1h-3zM26 2h3v1h-3zM30 2h3v1h-3zM35 2h3v1h-3zM43 2h3v1h-3zM48 2h3v1h-3zM56 2h3v1h-3zM60 2h3v1h-3zM0 3h3v1h-3zM8 3h3v1h-3zM12 3h3v1h-3zM17 3h3v1h-3zM21 3h3v1h-3zM26 3h3v1h-3zM30 3h3v1h-3zM35 3h3v1h-3zM43 3h3v1h-3zM48 3h3v1h-3zM56 3h3v1h-3zM60 3h3v1h-3zM0 4h3v1h-3zM8 4h3v1h-3zM12 4h3v1h-3zM17 4h6v1h-6zM26 4h6v1h-6zM35 4h3v1h-3zM43 4h3v1h-3zM48 4h5v1h-5zM56 4h7v1h-7zM0 5h3v1h-3zM8 5h3v1h-3zM12 5h3v1h-3zM17 5h7v1h-7zM26 5h7v1h-7zM35 5h3v1h-3zM43 5h3v1h-3zM48 5h5v1h-5zM56 5h7v1h-7zM0 6h3v1h-3zM8 6h3v1h-3zM12 6h3v1h-3zM17 6h3v1h-3zM21 6h3v1h-3zM26 6h3v1h-3zM30 6h3v1h-3zM35 6h3v1h-3zM43 6h3v1h-3zM48 6h3v1h-3zM58 6h3v1h-3zM0 7h3v1h-3zM8 7h3v1h-3zM12 7h3v1h-3zM17 7h3v1h-3zM21 7h3v1h-3zM26 7h3v1h-3zM30 7h3v1h-3zM35 7h3v1h-3zM43 7h3v1h-3zM48 7h3v1h-3zM58 7h3v1h-3zM0 8h6v1h-6zM8 8h7v1h-7zM17 8h7v1h-7zM26 8h7v1h-7zM35 8h6v1h-6zM43 8h3v1h-3zM48 8h3v1h-3zM58 8h3v1h-3zM0 9h6v1h-6zM8 9h7v1h-7zM17 9h7v1h-7zM26 9h7v1h-7zM35 9h6v1h-6zM43 9h3v1h-3zM48 9h3v1h-3zM58 9h3v1h-3z";

/**
 * SVG markup of the wordmark at `k` whole device pixels per art pixel.
 * opts.font picks the in-game text colours ("minecraft" | "modern"); opts.fill / opts.shadow override them,
 * and opts.shadow === false drops the shadow.
 */
export function wordmark(k = 2, opts = {}, dpr = (typeof window !== "undefined" && window.devicePixelRatio) || 1) {
  const px = Math.max(1, Math.round(k)) / dpr;
  const mc = (opts.font || "minecraft") === "minecraft";
  const fill = opts.fill || (mc ? "#FFFFFF" : "#EDEDED");
  const shadow = opts.shadow === false ? null : opts.shadow || (mc ? "#3F3F3F" : "#3B3B3B");
  return (
    `<svg class="wordmark" viewBox="0 0 64 11" width="${64 * px}" height="${11 * px}" shape-rendering="crispEdges" role="img" aria-label="Cobblify">` +
    (shadow ? `<path transform="translate(1 1)" fill="${shadow}" d="${WM}"/>` : "") +
    `<path fill="${fill}" d="${WM}"/></svg>`
  );
}
