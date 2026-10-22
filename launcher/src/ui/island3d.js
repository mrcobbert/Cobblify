/* island3d: today's island (island.json) as a reusable three.js component. Ported from the launcher prototype's
 * kit/island3d.js v1.6 (three r128, a global UMD) to an ES module on three 0.181.
 *
 *   const isl = mount(host, { edges: "art"|"screen"|"smooth", rest: "quarter"|"hero"|"letgo",
 *                             motion: "spin"|"step"|"drag"|"still", light: "smooth"|"flat", artAA, yaw0 });
 *   if (!isl) { show island-fallback.png instead }   // see "No WebGL" below
 *   isl.set({ built, buildStyle, stuck, bed, wool, gen, ingots, grey });   // targets; changes animate, never loop
 *     buildStyle: "part" | "layer" | "drop" | "partdrop" (1.5: each check's part, its blocks dropping in one by one;
 *     1.6: a stuck check's part is laid out as a grey ghost hanging above its place, and drops from there once fixed)
 *   isl.pace(x)                                   // partdrop's tempo: 1 is the setup build, < 1 replays it faster
 *   isl.buildEnd()                                // ms until the build in flight (the bed included) has landed
 *   isl.options({ edges, rest, motion, light });
 *   isl.fit(widthCss, heightCss, maxFraction?)   // -> { k, w, h, a }: whole device px per art px
 *   isl.firstFrame()                              // resolves once the first frame has been drawn
 *   isl.setActive(bool); isl.frames(); isl.destroy();
 *
 * No WebGL: mount() returns null (and appends nothing to the host) when the renderer cannot be created - three 0.181
 * needs a WebGL2 context - or the page lacks what the component needs (ResizeObserver). The caller then shows the still
 * image (./island-fallback.png). The prototype let the exception escape and its page caught it; null is the launcher's
 * contract for a scene that cannot boot (scene.js does the same).
 *
 * Drawing: flat face shading (top 98%, left 80%, right 60.8%, the Minecraft Wiki render ratios) from the turned normal
 * against a light fixed to the view; no lights, PBR, tone mapping, shadows, glow or bloom. The camera is the sprite's 2:1
 * dimetric view: one world unit maps to whole art pixels, so at a quarter turn with "art" edges it lands on exactly the
 * lab sprite's pixels. Nothing renders unless something changed or moves: spin and step run only while active and the
 * page is visible, and stop at once on setActive(false) or document.hidden.
 *
 * Colour: the face colours are the palette's sRGB bytes / 255, written straight into a vertex attribute and out of the
 * fragment shader, exactly as three r128 did. No THREE.Color is involved, so ColorManagement (on by default since r152)
 * never converts them, and the global flag is left alone. The renderer's outputColorSpace is set to linear so three adds
 * no output conversion (our ShaderMaterials never include colorspace_fragment anyway), and the art render target's
 * texture is NoColorSpace (three's default), so sampling it in the blit does not decode it as sRGB.
 */
import * as THREE from "three";
import islandData from "../island.json";

const QUARTER = Math.PI / 2, HERO = Math.PI / 6, SPIN_MS = 24000, STEP_EVERY = 4000, STEP_MS = 600, SETTLE_MS = 220, DRAG_PX = 160;
const K = (x, y, z) => ((x + 64) * 256 + (y + 64)) * 256 + (z + 64);
const hx = (h) => parseInt(h.slice(1), 16);
// prefers-reduced-motion, looked up on first use (never at import: the module loads under plain node too)
let reduceMqCache = null;
function reduceMq() {
  if (!reduceMqCache) {
    reduceMqCache = typeof window !== "undefined" && typeof window.matchMedia === "function"
      ? window.matchMedia("(prefers-reduced-motion: reduce)")
      : { matches: false, addEventListener() {}, removeEventListener() {} };
  }
  return reduceMqCache;
}
// buildStyle "partdrop" (Hook 1.5, island-NOTES.md): sequenced like "part" (a check adds its named part and nothing
// else), each block of that part dropping in like "drop" (from rest, h(1 - u²), bottom-up in dropQ order).
//   key      value    meaning (source)
//   h        1.5      blocks a block appears above its place (drop's 1.5); less where a block above or the canvas top
//                     leaves less room, 0 under a block already down (it is placed, it cannot fall there)
//   dur      220      ms of the fall itself (drop's 220)
//   winBase  120      ms: the part's stagger window is winBase + winPer x its visible blocks (drop's formula) ...
//   winPer   0.6      ... ms per visible block (interior rock rides along in its layer, uncounted)
//   winMax   420      ... capped so the biggest part lands 640 ms after the check (drop caps at 480 for 1/6 of all)
//   gap      16       ms: in a column a block starts at least one 60 Hz frame after the block under it, so nothing
//                     lands before the block under it (the intro's support rule)
//   beat     500      ms from the last block landing to the bed appearing (the user: same wait as the intro); the bed then drops like a block
//   jumpMax  900      ms: several checks at once drop their parts in order, in one window this long at most
//   hang     1        blocks the ghost hangs above its place (less where a block already down or the box top leaves less)
//   ghost    #646464  the ghost's one grey, whatever the part is made of (lit by the faces' shading like any block):
//                     the disabled rule's grey, and a builder's stand-in for blocks not placed yet. The part's own
//                     colours greyed (the sheet's rule, 45% over the ground) left the dark stone cliff a near-black
//                     smudge and the quartz columns a mid grey: one grey reads the same at every stop
// Every fall has the same gravity: a block that falls less than h takes dur x sqrt(fall / h) (220 ms for 1.5 blocks,
// 180 ms for the ghost's 1), so a short fall never drifts down slower than a long one.
// The ghost (1.6; stuck: the part its check waits on, shownSet().wait): its blocks are laid out one by one, bottom-up
// in the same order and window as its drop, once the part in flight has landed, and nothing falls (a stop is not
// progress). It then stands still, however long the stop lasts. When the check passes, each block takes its colour and
// drops the last block into its place, in the same order: the ghost is the part waiting to fall, never removed and
// dropped again. Parts reached before a stuck check still drop; only a greyed island never falls.
// With art edges every offset is whole art px (ceil, so nothing shows landed early). Reduced motion: no fall, no
// stagger; a change is one frame.
const PD = { h: 1.5, dur: 220, winBase: 120, winPer: 0.6, winMax: 420, gap: 16, beat: 500, jumpMax: 900, hang: 1, ghost: 0x646464 };
const fallMs = (h) => (h > 1e-6 ? PD.dur * Math.sqrt(Math.min(1, h / PD.h)) : 1);

// ------------------------------------------------------------------ colour: OKLab, the kit's shade(), wool
const lin = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
const unlin = (x) => { x = Math.min(1, Math.max(0, x)); return Math.round(255 * (x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055)); };
function oklab(hex) {
  const n = hx(hex), r = lin((n >> 16) & 255), g = lin((n >> 8) & 255), b = lin(n & 255);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b), m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b), s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s, 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s, 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s];
}
function fromLab(L, a, b) {
  const l = Math.pow(L + 0.3963377774 * a + 0.2158037573 * b, 3), m = Math.pow(L - 0.1055613458 * a - 0.0638541728 * b, 3), s = Math.pow(L - 0.0894841775 * a - 1.291485548 * b, 3);
  const rgb = [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s, -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s];
  return "#" + rgb.map((x) => unlin(x).toString(16).padStart(2, "0")).join("");
}
// identical to Cobblify.shade in the prototype's kit.js (OKLab lightness step, chroma multiplier)
function shade(hex, dL, cm = 1) { const [L, a, b] = oklab(hex); return fromLab(Math.min(0.985, Math.max(0.05, L + dL)), a * cm, b * cm); }
// Wool must read as wool on the real columns. Pale accents keep their hue at a wool lightness with more chroma;
// neutrals take three grey slots chosen against the columns' own quartz, clay and slab (lab, revision 1).
const NEUTRAL_SLOTS = [0.857, 0.645, 0.52];
export function woolFor(hex) {
  const [L, A, B] = oklab(hex), C = Math.hypot(A, B);
  if (C < 0.035) { const slot = L >= 0.85 ? NEUTRAL_SLOTS[0] : L >= 0.7 ? NEUTRAL_SLOTS[1] : NEUTRAL_SLOTS[2]; return shade(hex, slot - L, 0); }
  const target = Math.min(0.72, Math.max(0.5, L)), cm = C < 0.12 ? Math.min(3.2, 0.13 / C) : 1;
  return shade(hex, target - L, cm);
}
// Bed Wars team wool: the 1.8.9 wool block colours (Aqua = cyan wool). White and Gray take the neutral slots.
export const TEAM_WOOL = { Red: "#963430", Blue: "#2e388d", Green: "#41ae38", Yellow: "#b1a627", Aqua: "#2e6e89", White: "#dddddd", Pink: "#d08499", Gray: "#404040" };
export function teamWool(team) {
  const hex = TEAM_WOOL[team];
  if (!hex) return null;
  return team === "White" ? shade(hex, NEUTRAL_SLOTS[0] - oklab(hex)[0], 0) : team === "Gray" ? shade(hex, NEUTRAL_SLOTS[2] - oklab(hex)[0], 0) : hex;
}
// the sheet's disabled rule for the stuck part (desaturate, 45% over the ground) and for a greyed island (85% over it)
function mute(rgb, keep) {
  const r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255, y = 0.299 * r + 0.587 * g + 0.114 * b, G = 26;
  const f = (c) => Math.round(G + keep * (y + (c - y) * 0.15 - G));
  return (f(r) << 16) | (f(g) << 8) | f(b);
}

// ------------------------------------------------------------------ the model (built once per page)
let MODEL = null;
function model() {
  if (MODEL) return MODEL;
  const json = islandData;
  const pal = json.palette.map(hx), cells = [], at = new Map();
  const put = (x, y, z, ci, kind) => { const c = { x, y, z, ci, kind }; cells.push(c); at.set(K(x, y, z), c); return c; };
  for (let i = 0; i < json.solid.length; i += 4) put(json.solid[i], json.solid[i + 1], json.solid[i + 2], json.solid[i + 3], "solid");
  for (let i = 0; i < json.glow.length; i += 4) put(json.glow[i], json.glow[i + 1], json.glow[i + 2], json.glow[i + 3], "glow");
  // island.json is surface-only: fill rock layers (y <= -5) that are enclosed and covered, so build-up cuts read as
  // rock. The finished island never shows them (0 px differ at every pose, lab node check).
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const c of cells) { minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x); minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z); minY = Math.min(minY, c.y); maxY = Math.max(maxY, c.y); }
  const solidAt = (x, y, z) => at.has(K(x, y, z));
  for (let y = minY; y <= -5; y++) {
    const out = new Set([K(minX - 1, 0, minZ - 1)]), q = [[minX - 1, minZ - 1]];
    while (q.length) {
      const [x, z] = q.pop();
      for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const nx = x + dx, nz = z + dz;
        if (nx < minX - 1 || nx > maxX + 1 || nz < minZ - 1 || nz > maxZ + 1) continue;
        const k = K(nx, 0, nz);
        if (out.has(k) || solidAt(nx, y, nz)) continue;
        out.add(k); q.push([nx, nz]);
      }
    }
    const todo = [];
    for (let x = minX; x <= maxX; x++) for (let z = minZ; z <= maxZ; z++) {
      if (solidAt(x, y, z) || out.has(K(x, 0, z))) continue;
      let covered = false;
      for (let yy = y + 1; yy <= maxY && !covered; yy++) covered = solidAt(x, yy, z);
      if (covered) todo.push([x, z]);
    }
    for (const [x, z] of todo) put(x, y, z, y >= -7 ? 1 : 2, "fill");
  }
  // build-up orders. Part: 1 rock, 2 ground, 3 cliff, 4 columns, 5 roof and wool together, 6 the little tower's grey roof
  // at the very top (its eaves, ring and cap: stone at y >= 13), then the bed (1.5: the user's order). Layer: six slices of about equal block
  // count. Drop: every block ranked bottom-up with a little scatter (dropQ in 0..1).
  for (const c of cells) {
    const ci = c.ci, y = c.y;
    c.part = y <= -5 ? 1 : ci === 0 && y >= 13 ? 6 : ci === 7 || y >= 9 ? 5 : ci === 6 ? 4 : ci === 1 || ci === 2 ? 3 : ci === 0 && y >= -3 ? (c.x < -2 ? 3 : 4) : 2;
  }
  const surf = cells.filter((c) => c.kind !== "fill").map((c) => c.y).sort((a, b) => a - b);
  const cuts = [1, 2, 3, 4, 5].map((s) => surf[Math.floor((surf.length * s) / 6)]);
  for (const c of cells) { let s = 1; while (s <= 5 && c.y >= cuts[s - 1]) s++; c.layer = s; }
  const rnd = (x, y, z) => { const s = Math.sin(x * 127.1 + y * 311.7 + z * 74.7) * 43758.5453; return s - Math.floor(s); };
  const order = cells.slice().sort((p, q) => p.y + rnd(p.x, p.y, p.z) * 1.4 - (q.y + rnd(q.x, q.y, q.z) * 1.4));
  order.forEach((c, i) => { c.dropQ = i / order.length; });
  // each block's (x, z) column, bottom-up (partdrop: support and room above)
  const colsOf = new Map();
  for (const c of cells) { const key = (c.x + 64) * 256 + (c.z + 64); let l = colsOf.get(key); if (!l) colsOf.set(key, (l = [])); l.push(c); c.colCells = l; }
  for (const l of colsOf.values()) l.sort((p, q) => p.y - q.y);
  const third = 1 / 3;
  const bed = json.bedBoxes.map((b) => ({
    x0: (b.min[0] - 0.5) * third + 0.5, y0: (b.min[1] - 0.5) * third + 0.5, z0: (b.min[2] - 0.5) * third + 0.5,
    x1: (b.min[0] + b.size[0] - 0.5) * third + 0.5, y1: (b.min[1] + b.size[1] - 0.5) * third + 0.5, z1: (b.min[2] + b.size[2] - 0.5) * third + 0.5, rgb: pal[b.c],
  }));
  MODEL = { pal, cells, at, bed, groundY: -3 };
  return MODEL;
}
/** The "part" build order as data: six arrays (parts 1..6), each block as { x, y, z, ci, kind } with kind "solid",
 *  "glow" or "fill" (interior rock added under the surface). The bed is not a voxel; it comes after part 6. */
export function buildParts() {
  const parts = [[], [], [], [], [], []];
  for (const c of model().cells) parts[c.part - 1].push({ x: c.x, y: c.y, z: c.z, ci: c.ci, kind: c.kind });
  return parts;
}
// the forge: our addition to the model, a 2x2 dark slab on the grass behind the bed, ingots in three rows stacking up
const FORGE = { x: 3, z: -11, pad: 0x4a4a4a, iron: 0xd2d2d2 };
const INGOT_SLOTS = [];
for (let layer = 0; layer < 4; layer++) for (const iz of [0, 2, 4]) INGOT_SLOTS.push([2, iz, layer]);
INGOT_SLOTS.length = 10;

// bounds in art px about the pivot, over a set of yaws
function bounds(list, a, yaws) {
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const t of yaws) {
    const c = Math.cos(t), s = Math.sin(t);
    for (const b of list) for (const X of [b.x0, b.x1]) for (const Z of [b.z0, b.z1]) {
      const x = X * c + Z * s, z = -X * s + Z * c, sx = (x - z) * a, sy = ((x + z) / 2) * a;
      minX = Math.min(minX, sx); maxX = Math.max(maxX, sx); minY = Math.min(minY, sy - b.y1 * a); maxY = Math.max(maxY, sy - b.y0 * a);
    }
  }
  return { minX: Math.floor(minX), maxX: Math.ceil(maxX), minY: Math.floor(minY), maxY: Math.ceil(maxY) };
}
const BOXES = {};
function boxFor(a) {
  if (BOXES[a]) return BOXES[a];
  const m = model(), list = m.cells.filter((c) => c.kind !== "fill").map((c) => ({ x0: c.x, y0: c.y, z0: c.z, x1: c.x + 1, y1: c.y + 1, z1: c.z + 1 }));
  list.push({ x0: FORGE.x, y0: -3, z0: FORGE.z, x1: FORGE.x + 2, y1: -1.5, z1: FORGE.z + 2 });
  const b = bounds(list, a, Array.from({ length: 48 }, (_, i) => (i * Math.PI) / 24));
  b.minY -= a; // one block of headroom: the stuck part hangs one block up
  BOXES[a] = b;
  return b;
}
// partdrop: the blocks a cell can rise and still be wholly inside the resting box at every yaw it may turn through
// (the 48 of boxFor), so a drop is never cut at the canvas edge, whatever the motion does meanwhile
const ROOMS = {};
function roomOf(c, a) {
  const R = ROOMS[a] || (ROOMS[a] = new Map());
  let r = R.get(c);
  if (r === undefined) {
    const top0 = boxFor(a).minY;
    let top = Infinity;
    for (let i = 0; i < 48; i++) {
      const t = (i * Math.PI) / 24, cs = Math.cos(t), sn = Math.sin(t);
      for (const X of [c.x, c.x + 1]) for (const Z of [c.z, c.z + 1]) { const x = X * cs + Z * sn, z = -X * sn + Z * cs; top = Math.min(top, ((x + z) / 2 - (c.y + 1)) * a); }
    }
    r = Math.max(0, (top - top0) / a);
    R.set(c, r);
  }
  return r;
}

// ------------------------------------------------------------------ shaders
// GLSL1-style sources: three 0.181 (WebGL2 only) compiles ShaderMaterial as GLSL ES 3.00 and its prefix maps
// attribute/varying/texture2D/gl_FragColor onto in/out/texture/pc_fragColor.
const VERT = "attribute vec4 col; attribute float ao; varying vec3 vCol; void main(){ vec3 n = normalize(mat3(modelMatrix) * normal);" +
  " float b = n.y > 0.5 ? 0.98 : (n.y < -0.5 ? 0.5 : 0.704 + 0.096 * (n.z - n.x)); vCol = col.rgb * mix(b, 1.0, col.a) * ao;" +
  " gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0); }";
const FRAG = "varying vec3 vCol; void main(){ gl_FragColor = vec4(vCol, 1.0); }";
// art edges: the art-pixel render is scaled up by whole device pixels with nearest sampling
const BLIT_V = "uniform vec2 span; uniform vec2 off; varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(-1.0 + 2.0 * (off.x + uv.x * span.x), 1.0 - 2.0 * (off.y + (1.0 - uv.y) * span.y), 0.0, 1.0); }";
const BLIT_F = "uniform sampler2D map; varying vec2 vUv; void main(){ gl_FragColor = texture2D(map, vUv); }";

// ------------------------------------------------------------------ an instance
/**
 * Mounts the island in `host` (a canvas is appended to it).
 * @returns the instance, or null when WebGL2 (or ResizeObserver) is unavailable - see the header.
 */
export function mount(host, opts = {}) {
  const M = model();
  const O = { edges: "art", rest: "quarter", motion: "spin", artAA: true, light: "smooth", ...opts }; // artAA: not in the contract; false = art pops while turning
  const T = { built: 1, buildStyle: "part", stuck: false, bed: true, wool: null, gen: false, ingots: 0, grey: false };
  let woolRgb = null;
  let active = true, frames = 0, raf = 0, stepTimer = 0, destroyed = false;
  // yaw0 (degrees): the angle it opens at (a page's "front"); a drag still settles to the nearest rest
  let yaw = O.yaw0 != null ? (O.yaw0 * Math.PI) / 180 : O.rest === "hero" ? HERO : 0, spin = null, ease = null, drag = null;
  let a = 6, k = 1, box = boxFor(6), fitArgs = null;
  const tw = { cells: new Map(), bed: null, pad: null, ingots: [] };
  // partdrop's ghost: cell -> { t0: when it is laid out, h: how high it hangs }; it lives as long as the stop does
  const gh = new Map();
  let ghUntil = 0; // the last ghost block's t0 while they are still being laid out (frames run until then)
  let gid = 1, contentDirty = true, dirty = true, shown = null, wasMoving = false;
  // firstFrame(): resolves once a frame has been drawn since mount
  let drawnOnce = false, firstWaiters = [];

  // three.js objects
  const scene = new THREE.Scene(), grp = new THREE.Group(), geo = new THREE.BufferGeometry();
  const cam = new THREE.OrthographicCamera(-1, 1, 1, -1, 1, 400);
  cam.position.set(100, 100, 100); cam.lookAt(0, 0, 0);
  const mat = new THREE.ShaderMaterial({ vertexShader: VERT, fragmentShader: FRAG });
  const mesh = new THREE.Mesh(geo, mat);
  mesh.frustumCulled = false; grp.add(mesh); scene.add(grp);
  const blitScene = new THREE.Scene(), blitCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1);
  const blitMat = new THREE.ShaderMaterial({ vertexShader: BLIT_V, fragmentShader: BLIT_F, uniforms: { map: { value: null }, span: { value: new THREE.Vector2(1, 1) }, off: { value: new THREE.Vector2(0, 0) } }, depthTest: false, depthWrite: false });
  const blitGeo = new THREE.PlaneGeometry(2, 2), blit = new THREE.Mesh(blitGeo, blitMat);
  blit.frustumCulled = false; blitScene.add(blit);
  let rt = null, rtMS = null, renderer = null, canvas = null, ro = null;

  function makeRenderer() {
    const smooth = O.edges === "smooth";
    if (renderer && renderer._smooth === smooth) return;
    if (renderer) { renderer.dispose(); canvas.remove(); ro.disconnect(); }
    // throws when there is no WebGL2 context (three 0.181 has no WebGL1 path)
    renderer = new THREE.WebGLRenderer({ antialias: smooth, alpha: true, premultipliedAlpha: true, powerPreference: "low-power" });
    renderer._smooth = smooth;
    // r128 had no output colour space; linear keeps three from converting anything (see the header)
    renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
    renderer.setPixelRatio(1);
    renderer.setClearColor(0x000000, 0);
    canvas = renderer.domElement;
    canvas.style.display = "block";
    canvas.style.touchAction = "none";
    if (!ro) ro = new ResizeObserver(onResize);
    host.appendChild(canvas);
    sizeCanvas();
    try { ro.observe(canvas, { box: "device-pixel-content-box" }); } catch (e) { ro.observe(canvas); }
    bindDrag();
    dirty = true;
  }
  // the backing store equals the canvas's device-pixel box, so whole art pixels stay whole
  let devW = 1, devH = 1;
  function onResize(entries) {
    for (const e of entries) {
      const b = e.devicePixelContentBoxSize && e.devicePixelContentBoxSize[0];
      const dpr = window.devicePixelRatio || 1;
      const w = b ? b.inlineSize : Math.round(e.contentRect.width * dpr), h = b ? b.blockSize : Math.round(e.contentRect.height * dpr);
      if (w && h && (w !== devW || h !== devH)) { devW = w; devH = h; renderer.setSize(w, h, false); dirty = true; }
    }
    if (dirty) kick();
  }
  function sizeCanvas() {
    const dpr = window.devicePixelRatio || 1, W = (box.maxX - box.minX) * k, H = (box.maxY - box.minY) * k;
    canvas.style.width = W / dpr + "px"; canvas.style.height = H / dpr + "px";
    devW = W; devH = H; renderer.setSize(W, H, false);
  }

  // ---------------- what to draw
  const stepIn = (c, s) => (s === "layer" ? c.layer : c.part);
  function shownSet(t) {
    // visible(c) and waiting(c) for a target: whole steps for part and layer, a block fraction for drop
    const done = Math.floor(t.built * 6 + 1e-6);
    if (t.buildStyle === "drop") return { vis: (c) => c.dropQ < t.built - 1e-9 || t.built >= 1, wait: (c) => t.stuck && t.built < 1 && c.dropQ >= t.built && c.dropQ < t.built + 1 / 6 };
    return { vis: (c) => t.built >= 1 || stepIn(c, t.buildStyle) <= done, wait: (c) => t.stuck && t.built < 1 && stepIn(c, t.buildStyle) === done + 1 };
  }
  const padCell = () => M.at.get(K(FORGE.x, -4, FORGE.z));
  function lift(t, now) {
    if (!t) return 0;
    if (now < t.t0) return null;
    const u = (now - t.t0) / t.dur;
    if (u >= 1) return 0;
    const y = t.h * (1 - u * u);
    return t.snap && O.edges === "art" ? Math.ceil(y * a - 1e-6) / a : y; // partdrop: whole art px, never early
  }
  const live = (t, now) => t && now < t.t0 + t.dur;
  let lastSig = NaN;
  // partdrop: what the build tweens show now, hashed; an unchanged tick (the slow start, the beat) is not redrawn
  function tweenSig(now) {
    let h = 2166136261;
    const mh = (v) => { h = Math.imul(h ^ (v | 0), 16777619); };
    const one = (t) => { if (!t) return mh(-2); const y = lift(t, now); mh(y === null ? -1 : Math.round(y * 4096)); };
    for (const t of tw.cells.values()) one(t);
    one(tw.bed); one(tw.pad); for (const t of tw.ingots) one(t);
    if (now < ghUntil + 1) for (const g of gh.values()) mh(now >= g.t0 ? 1 : 0); // ghost blocks laid out so far
    return h;
  }
  function tweensLive(now) {
    if (live(tw.bed, now) || live(tw.pad, now) || tw.ingots.some((t) => live(t, now))) return true;
    for (const t of tw.cells.values()) if (live(t, now)) return true;
    return now < ghUntil + 1;
  }
  const hasTweens = () => tw.cells.size || tw.bed || tw.pad || tw.ingots.length || ghUntil;
  // the ghost itself stays (it is the stop's state); only its layout clock ends
  function clearTweens() { tw.cells.clear(); tw.bed = tw.pad = null; tw.ingots = []; ghUntil = 0; }
  // a ghost block (or a released one waiting its turn) is PD.ghost, h blocks up, on whole art px; ghosts hanging
  // equally high hide the faces between them, never a real block's
  const ghostY = (h) => (O.edges === "art" ? Math.floor(h * a + 1e-6) / a : h);

  function buildItems(now) {
    const s = shownSet(T), out = [], pal = M.pal, grey = T.grey;
    const colour = (rgb) => (grey ? mute(rgb, 0.85) : rgb);
    const whole = T.built >= 1 && !tw.cells.size; // interior rock is only needed for cuts
    const pd = T.buildStyle === "partdrop";
    const box3 = (x0, y0, z0, x1, y1, z1, rgb, unit, grp, yoff, glow) => out.push({ x0, y0, z0, x1, y1, z1, rgb, unit, grp, yoff, glow });
    const ghost = (c, h) => { const y = ghostY(h); box3(c.x, c.y, c.z, c.x + 1, c.y + 1, c.z + 1, PD.ghost, true, -1 - Math.round(y * 1024), y, false); };
    for (const c of M.cells) {
      if (c.kind === "fill" && whole) continue;
      let rgb = c.ci === 7 && woolRgb != null ? woolRgb : pal[c.ci];
      if (!s.vis(c)) {
        if (pd) { const g = gh.get(c); if (g && now >= g.t0) ghost(c, g.h); }
        else if (s.wait(c)) box3(c.x, c.y, c.z, c.x + 1, c.y + 1, c.z + 1, PD.ghost, true, -1, 1, false);
        continue;
      }
      const t = tw.cells.get(c);
      if (t && t.pre && now < t.t0) { ghost(c, t.h); continue; } // released, still hanging until its turn
      const y = lift(t, now);
      if (y === null) continue;
      box3(c.x, c.y, c.z, c.x + 1, c.y + 1, c.z + 1, colour(rgb), true, y > 0 ? t.grp : 0, y || 0, c.kind === "glow");
    }
    if (T.bed && T.built >= 1) {
      const y = lift(tw.bed, now);
      if (y !== null) M.bed.forEach((b) => box3(b.x0, b.y0, b.z0, b.x1, b.y1, b.z1, colour(b.rgb), false, y > 0 ? tw.bed.grp : 0, y || 0, false));
    }
    const pc = padCell();
    if (T.gen && (!pc || s.vis(pc))) {
      const y = lift(tw.pad, now), y0 = M.groundY;
      if (y !== null) {
        box3(FORGE.x, y0, FORGE.z, FORGE.x + 2, y0 + 1 / 3, FORGE.z + 2, colour(FORGE.pad), false, y > 0 ? tw.pad.grp : 0, y || 0, false);
        for (let i = 0; i < Math.min(10, T.ingots); i++) {
          const yi = lift(tw.ingots[i], now);
          if (yi === null) continue;
          const [ix, iz, layer] = INGOT_SLOTS[i], x0 = FORGE.x + ix / 3, z0 = FORGE.z + iz / 3, yy = y0 + (1 + layer) / 3;
          box3(x0, yy, z0, x0 + 2 / 3, yy + 1 / 3, z0 + 1 / 3, colour(FORGE.iron), false, yi > 0 ? tw.ingots[i].grp : 0, (yi || 0) + (y || 0), false);
        }
      }
    }
    return out;
  }
  // vertex buffers that grow as needed
  function buffers(geometry, attrs) {
    const B = { cap: 0, n: 0, geometry, attrs, arr: {} };
    B.ensure = (need) => {
      if (need <= B.cap) return;
      B.cap = Math.ceil(need * 1.25);
      for (const [name, size] of attrs) { B.arr[name] = new Float32Array(B.cap * size); geometry.setAttribute(name, new THREE.BufferAttribute(B.arr[name], size).setUsage(THREE.DynamicDrawUsage)); }
    };
    // upload only the part in use (r159+: update ranges replace the old updateRange)
    B.done = () => {
      for (const [name, size] of attrs) {
        const at = geometry.attributes[name];
        if (!at) continue;
        at.clearUpdateRanges(); at.addUpdateRange(0, Math.max(1, B.n) * size); at.needsUpdate = true;
      }
      geometry.setDrawRange(0, B.n);
    };
    return B;
  }
  const BO = buffers(geo, [["position", 3], ["normal", 3], ["col", 4], ["ao", 1]]);
  function buildGeometry(list) {
    // Smooth lighting (Minecraft's ambient occlusion): occluders are the opaque whole blocks that sit at rest now.
    // A box that moves is drawn flat (in Minecraft a falling block is an entity, lit flat); blocks around it get their
    // occlusion from it once it has landed.
    const smooth = O.light !== "flat";
    const aoOcc = smooth ? new Set() : null;
    if (smooth) for (const b of list) if (b.unit && b.grp === 0 && !b.yoff) aoOcc.add(K(b.x0, b.y0, b.z0));
    const occ = new Map();
    for (const b of list) if (b.unit) occ.set(K(b.x0, b.y0, b.z0), b.grp);
    const hid = (x, y, z, g) => occ.get(K(x, y, z)) === g; // only boxes in the same group (at rest: 0) cull each other
    BO.ensure(list.length * 30);
    BO.n = 0;
    let cr = 0, cg = 0, cb = 0, ca = 0, aoCell = null;
    const P = BO.arr.position, N = BO.arr.normal, C = BO.arr.col, A = BO.arr.ao;
    const v = (p, nx, ny, nz, ao) => {
      const n = BO.n;
      P[n * 3] = p[0]; P[n * 3 + 1] = p[1]; P[n * 3 + 2] = p[2]; N[n * 3] = nx; N[n * 3 + 1] = ny; N[n * 3 + 2] = nz;
      C[n * 4] = cr; C[n * 4 + 1] = cg; C[n * 4 + 2] = cb; C[n * 4 + 3] = ca;
      A[n] = ao;
      BO.n++;
    };
    // a face vertex: the two side blocks and the corner block on the face's outward layer, Minecraft's four levels
    // (1.0, 0.8, 0.6, 0.4: each opaque neighbour counts 0.2 in the four-sample average; two sides hide the corner)
    const has = (x, y, z) => aoOcc.has(K(x, y, z));
    const aoAt = (p, nx, ny, nz) => {
      const [cx, cy, cz] = aoCell, bx = cx + nx, by = cy + ny, bz = cz + nz;
      const sx = p[0] > cx + 0.5 ? 1 : -1, sy = p[1] > cy + 0.5 ? 1 : -1, sz = p[2] > cz + 0.5 ? 1 : -1;
      let s1, s2, c;
      if (nx) { s1 = has(bx, by + sy, bz); s2 = has(bx, by, bz + sz); c = has(bx, by + sy, bz + sz); }
      else if (ny) { s1 = has(bx + sx, by, bz); s2 = has(bx, by, bz + sz); c = has(bx + sx, by, bz + sz); }
      else { s1 = has(bx + sx, by, bz); s2 = has(bx, by + sy, bz); c = has(bx + sx, by + sy, bz); }
      return 1 - 0.2 * (s1 && s2 ? 3 : s1 + s2 + c);
    };
    const face = (p0, p1, p2, p3, nx, ny, nz) => {
      const ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2], wx = p2[0] - p0[0], wy = p2[1] - p0[1], wz = p2[2] - p0[2];
      if ((uy * wz - uz * wy) * nx + (uz * wx - ux * wz) * ny + (ux * wy - uy * wx) * nz < 0) { const t = p1; p1 = p3; p3 = t; }
      if (!aoCell) { v(p0, nx, ny, nz, 1); v(p1, nx, ny, nz, 1); v(p2, nx, ny, nz, 1); v(p0, nx, ny, nz, 1); v(p2, nx, ny, nz, 1); v(p3, nx, ny, nz, 1); return; }
      const a0 = aoAt(p0, nx, ny, nz), a1 = aoAt(p1, nx, ny, nz), a2 = aoAt(p2, nx, ny, nz), a3 = aoAt(p3, nx, ny, nz);
      // split along the brighter diagonal, so one dark corner stays a corner (Minecraft flips the quad the same way)
      if (a0 + a2 < a1 + a3) { v(p1, nx, ny, nz, a1); v(p2, nx, ny, nz, a2); v(p3, nx, ny, nz, a3); v(p1, nx, ny, nz, a1); v(p3, nx, ny, nz, a3); v(p0, nx, ny, nz, a0); }
      else { v(p0, nx, ny, nz, a0); v(p1, nx, ny, nz, a1); v(p2, nx, ny, nz, a2); v(p0, nx, ny, nz, a0); v(p2, nx, ny, nz, a2); v(p3, nx, ny, nz, a3); }
    };
    const boxFaces = (b) => {
      const yo = b.yoff || 0, x0 = b.x0, y0 = b.y0 + yo, z0 = b.z0, x1 = b.x1, y1 = b.y1 + yo, z1 = b.z1, g = b.grp, u = b.unit;
      cr = ((b.rgb >> 16) & 255) / 255; cg = ((b.rgb >> 8) & 255) / 255; cb = (b.rgb & 255) / 255; ca = b.glow ? 1 : 0;
      // occlusion only for whole blocks at rest that are not light sources (Minecraft skips it for emitters)
      aoCell = smooth && u && !b.glow && aoOcc.has(K(b.x0, b.y0, b.z0)) ? [b.x0, b.y0, b.z0] : null;
      if (!(u && hid(b.x0, b.y0 + 1, b.z0, g))) face([x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0], 0, 1, 0);
      if (!(u && hid(b.x0, b.y0, b.z0 + 1, g))) face([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1], 0, 0, 1);
      if (!(u && hid(b.x0, b.y0, b.z0 - 1, g))) face([x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [x1, y0, z0], 0, 0, -1);
      if (!(u && hid(b.x0 + 1, b.y0, b.z0, g))) face([x1, y0, z0], [x1, y1, z0], [x1, y1, z1], [x1, y0, z1], 1, 0, 0);
      if (!(u && hid(b.x0 - 1, b.y0, b.z0, g))) face([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0], -1, 0, 0);
    };
    for (const b of list) boxFaces(b);
    BO.done();
  }

  // ---------------- drawing
  // The art-pixel render target. Two things keep it on r128's exact pixels:
  // - NoColorSpace (three's default, spelled out): the blit samples the bytes as written, with no sRGB decode.
  // - A 16-bit depth buffer, as r128 allocated. three 0.181 allocates 24 bits unless a depth texture says otherwise;
  //   where two faces meet at an edge the depth test then picks a different winner for the odd pixel or MSAA sample
  //   (measured: the 4x MSAA turning pass differed from r128 by one art pixel with 24 bits, by none with 16).
  //   The depth is never read, so the MSAA target does not resolve it.
  // A depth texture is not resized by setSize, so a new size makes a new target (sizes change only on fit()).
  function artTarget(old, w, h, samples) {
    if (old && old.width === w && old.height === h) return old;
    if (old) old.dispose();
    const t = new THREE.WebGLRenderTarget(w, h, {
      minFilter: THREE.NearestFilter, magFilter: THREE.NearestFilter, colorSpace: THREE.NoColorSpace, generateMipmaps: false,
      depthBuffer: true, stencilBuffer: false, depthTexture: new THREE.DepthTexture(w, h, THREE.UnsignedShortType),
      resolveDepthBuffer: false, samples,
    });
    t.texture.generateMipmaps = false;
    return t;
  }
  function draw() {
    const art = O.edges === "art";
    // centre the art box on whole multiples of k, so the art grid lines up with device pixels
    const W = (box.maxX - box.minX) * k, H = (box.maxY - box.minY) * k;
    const offX = Math.max(0, Math.floor((devW - W) / 2 / k)) * k, offY = Math.max(0, Math.floor((devH - H) / 2 / k)) * k;
    let Wr = devW, Hr = devH, ox = offX - box.minX * k, oy = offY - box.minY * k, p = a * k;
    // art: render at art resolution (texel 0 at device 0, 0: offX and offY are whole multiples of k)
    if (art) { Wr = Math.ceil(devW / k); Hr = Math.ceil(devH / k); ox = offX / k - box.minX; oy = offY / k - box.minY; p = a; }
    const sx = p * Math.SQRT2, sy = (p * Math.sqrt(6)) / 2;
    cam.left = -ox / sx; cam.right = (Wr - ox) / sx; cam.top = oy / sy; cam.bottom = (oy - Hr) / sy;
    cam.updateProjectionMatrix();
    grp.rotation.y = yaw;
    if (art) {
      // While it turns, the art-pixel pass is antialiased (4x MSAA at art resolution): edges fade between colours
      // instead of popping whole art pixels. At rest it is crisp again, on exactly the sprite's pixels.
      // (r128 needed WebGLMultisampleRenderTarget and a WebGL2 context for this; three 0.181 is WebGL2 only and takes
      // `samples` on a plain WebGLRenderTarget.)
      const moving = O.artAA !== false && (spin || ease || drag);
      let target;
      if (moving) target = rtMS = artTarget(rtMS, Wr, Hr, 4);
      else target = rt = artTarget(rt, Wr, Hr, 0);
      renderer.setRenderTarget(target);
      renderer.clear();
      renderer.render(scene, cam);
      renderer.setRenderTarget(null);
      blitMat.uniforms.map.value = target.texture;
      blitMat.uniforms.span.value.set((Wr * k) / devW, (Hr * k) / devH);
      renderer.clear();
      renderer.render(blitScene, blitCam);
    } else {
      renderer.setRenderTarget(null);
      renderer.clear();
      renderer.render(scene, cam);
    }
    frames++;
    if (!drawnOnce) { drawnOnce = true; const w = firstWaiters; firstWaiters = []; for (const f of w) f(); }
  }
  const restYaw = (y) => (O.rest === "letgo" ? y : O.rest === "hero" ? Math.round((y - HERO) / QUARTER) * QUARTER + HERO : Math.round(y / QUARTER) * QUARTER);
  const reduced = () => reduceMq().matches;
  const auto = () => !reduced() && (O.motion === "spin" || O.motion === "step");
  const canRun = () => active && !document.hidden && !destroyed;
  const draggable = () => O.motion === "drag" || ((O.motion === "spin" || O.motion === "step") && reduced());

  function kick() { if (!raf && !document.hidden && !destroyed) raf = requestAnimationFrame(tick); }
  function tick() {
    raf = 0;
    if (destroyed) return;
    const now = performance.now();
    let more = false;
    if (hasTweens()) {
      if (tweensLive(now)) {
        if (T.buildStyle !== "partdrop") contentDirty = true;
        else { const sg = tweenSig(now); if (sg !== lastSig) { lastSig = sg; contentDirty = true; } }
        more = true;
      } else { clearTweens(); contentDirty = true; lastSig = NaN; }
    }
    if (spin) { yaw = spin.yaw0 + ((now - spin.t0) / SPIN_MS) * Math.PI * 2; dirty = true; more = true; }
    if (ease) {
      const u = Math.min(1, (now - ease.t0) / ease.dur), e = ease.inOut ? (u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2) : 1 - Math.pow(1 - u, 3);
      yaw = ease.from + (ease.to - ease.from) * e; dirty = true;
      if (u >= 1) { yaw = ease.to; ease = null; } else more = true;
    }
    if (drag === null && wasMoving && !spin && !ease) dirty = true; // the frame after motion: crisp again
    wasMoving = !!(spin || ease || drag);
    if (contentDirty) { buildGeometry(buildItems(now)); contentDirty = false; dirty = true; }
    if (dirty) { draw(); dirty = false; }
    if (more) kick();
  }

  // ---------------- motion
  function startMotion() {
    stopTimers();
    if (!canRun() || !auto()) return;
    if (O.motion === "spin") { spin = { t0: performance.now(), yaw0: yaw }; kick(); }
    else scheduleStep();
  }
  function scheduleStep() {
    clearTimeout(stepTimer);
    stepTimer = setTimeout(() => {
      if (!canRun() || O.motion !== "step" || reduced()) return;
      const from = yaw, to = restYaw(yaw) + QUARTER;
      ease = { from, to, t0: performance.now(), dur: STEP_MS, inOut: true };
      kick();
      scheduleStep();
    }, STEP_EVERY);
  }
  function stopTimers() { clearTimeout(stepTimer); stepTimer = 0; spin = null; }
  // stop at once: spin and step halt where they are and come to rest in one frame (no settle animation)
  function halt() {
    const was = spin || ease;
    stopTimers(); ease = null;
    const r = restYaw(yaw);
    if (was || r !== yaw) { yaw = r; dirty = true; }
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
    if (dirty && !document.hidden) kick();
  }
  function settle(animate) {
    const to = restYaw(yaw);
    if (Math.abs(to - yaw) < 1e-6) return;
    if (!animate || reduced()) { yaw = to; ease = null; dirty = true; kick(); return; }
    ease = { from: yaw, to, t0: performance.now(), dur: SETTLE_MS };
    kick();
  }
  function bindDrag() {
    canvas.addEventListener("pointerdown", (e) => {
      if (!draggable() || e.button !== 0) return;
      drag = { x0: e.clientX, yaw0: yaw }; ease = null;
      canvas.setPointerCapture(e.pointerId);
    });
    canvas.addEventListener("pointermove", (e) => { if (!drag) return; yaw = drag.yaw0 + ((e.clientX - drag.x0) / DRAG_PX) * QUARTER; dirty = true; kick(); });
    const end = () => { if (!drag) return; drag = null; settle(true); };
    canvas.addEventListener("pointerup", end);
    canvas.addEventListener("pointercancel", end);
  }
  // hidden: motion stops
  const onVis = () => { if (document.hidden) halt(); else { dirty = true; kick(); startMotion(); } };
  const onReduce = () => { halt(); startMotion(); };
  let dprMq = null;
  const watchDpr = () => {
    if (dprMq) dprMq.removeEventListener("change", onDpr);
    if (typeof window.matchMedia !== "function") return;
    dprMq = window.matchMedia(`(resolution: ${window.devicePixelRatio || 1}dppx)`);
    dprMq.addEventListener("change", onDpr);
  };
  function onDpr() { watchDpr(); if (fitArgs) api.fit(...fitArgs); else { sizeCanvas(); dirty = true; kick(); } }

  // ---------------- partdrop: the parts just added, in part order, each block dropping in like "drop"
  function partDrop(added, ns, now) {
    const byPart = new Map();
    for (const c of added) { let l = byPart.get(c.part); if (!l) byPart.set(c.part, (l = [])); l.push(c); }
    const parts = [...byPart.keys()].sort((p, q) => p - q);
    // each part's stagger window by its visible blocks (drop's formula), all of them squeezed into jumpMax at most
    const wins = parts.map((p) => { let n = 0; for (const c of byPart.get(p)) if (c.kind !== "fill") n++; return Math.min(PD.winMax, PD.winBase + n * PD.winPer); });
    const sum = wins.reduce((s, w) => s + w, 0), sc = (sum > PD.jumpMax ? PD.jumpMax / sum : 1) * pace;
    const start = new Map();
    let off = 0;
    parts.forEach((p, j) => {
      const l = byPart.get(p).sort((x, y) => x.dropQ - y.dropQ), n = l.length, w = wins[j] * sc;
      l.forEach((c, i) => start.set(c, now + off + (n > 1 ? (i / (n - 1)) * w : 0)));
      off += w;
    });
    // In each column, from the top down: how far a block falls. A new block falls h (1.5), less where a block already
    // there or the box top leaves less room; a released ghost block falls only its hang. A block never falls further
    // than the new block above it plus the air between them, so a stack comes down together and never through itself.
    const cols = new Set();
    for (const c of added) cols.add(c.colCells);
    const fall = new Map();
    for (const col of cols) {
      let capH = Infinity, capY = Infinity; // the lowest thing above so far: its fall (0 when it is already down) and y
      for (let i = col.length - 1; i >= 0; i--) {
        const c = col[i];
        if (start.has(c)) {
          const g = gh.get(c);
          let h = g ? ghostY(g.h) : Math.min(PD.h, roomOf(c, a));
          if (capY !== Infinity) h = Math.min(h, capH + (capY - (c.y + 1)));
          h = Math.max(0, h);
          fall.set(c, h); capH = h; capY = c.y;
        } else if (ns.vis(c)) { capH = 0; capY = c.y; }
      }
    }
    // support: in its column a block starts at least `gap` after the block under it, new or still falling, so it
    // never lands first; a released ghost block goes no earlier than it was laid out
    for (const col of cols) {
      let below = -Infinity;
      for (const c of col) {
        if (start.has(c)) {
          const g = gh.get(c), h = fall.get(c);
          const t0 = Math.max(start.get(c), below + PD.gap * pace, g ? g.t0 + PD.gap : -Infinity);
          tw.cells.set(c, { t0, dur: fallMs(h) * pace, h, grp: gid++, snap: true, pre: !!g });
          below = t0;
        } else { const t = tw.cells.get(c); if (t) below = Math.max(below, t.t0); }
      }
    }
    for (const c of added) gh.delete(c);
  }
  // ---------------- partdrop's ghost: lay out the part a stuck check waits on (wait(c)), keep it while the stop lasts
  function syncGhost(ns, now, animate) {
    const W = new Set();
    if (T.buildStyle === "partdrop" && T.stuck && !T.grey && T.built < 1) for (const c of M.cells) if (ns.wait(c)) W.add(c);
    for (const c of [...gh.keys()]) if (!W.has(c)) gh.delete(c);
    if (!animate) for (const g of gh.values()) g.t0 = -Infinity;
    const todo = [...W].filter((c) => !gh.has(c));
    if (!todo.length) return;
    // hang: `hang` blocks up, less where a block already down or the box top leaves less room; a ghost never hangs
    // higher than the ghost above it allows, so a stacked part keeps its shape
    const cols = new Set(todo.map((c) => c.colCells)), hang = new Map();
    for (const col of cols) {
      let capH = Infinity, capY = Infinity;
      for (let i = col.length - 1; i >= 0; i--) {
        const c = col[i];
        if (W.has(c)) {
          let h = Math.min(PD.hang, roomOf(c, a));
          if (capY !== Infinity) h = Math.min(h, capH + (capY - (c.y + 1)));
          h = Math.max(0, h); hang.set(c, h); capH = h; capY = c.y;
        } else if (ns.vis(c)) { capH = 0; capY = c.y; }
      }
    }
    if (!animate) { for (const c of todo) gh.set(c, { t0: -Infinity, h: hang.get(c) }); return; }
    // laid out once the part in flight has landed, bottom-up in drop order over the drop's window, each block a frame
    // after the one under it
    let t = now;
    for (const tt of tw.cells.values()) t = Math.max(t, tt.t0 + tt.dur);
    todo.sort((p, q) => p.dropQ - q.dropQ);
    let n = 0; for (const c of todo) if (c.kind !== "fill") n++;
    const w = Math.min(PD.winMax, PD.winBase + n * PD.winPer), at = new Map();
    todo.forEach((c, i) => at.set(c, t + (todo.length > 1 ? (i / (todo.length - 1)) * w : 0)));
    for (const col of cols) {
      let below = -Infinity;
      for (const c of col) {
        if (at.has(c)) { const v = Math.max(at.get(c), below + PD.gap); at.set(c, v); below = v; }
        else if (gh.has(c)) below = Math.max(below, gh.get(c).t0);
      }
    }
    for (const c of todo) { const v = at.get(c); gh.set(c, { t0: v, h: hang.get(c) }); ghUntil = Math.max(ghUntil, v); }
  }

  // ---------------- targets
  function applyTarget(prev) {
    const now = performance.now(), anim = !reduced() && !document.hidden && prev;
    if (!anim) { clearTweens(); if (T.buildStyle === "partdrop") syncGhost(shownSet(T), now, false); else gh.clear(); contentDirty = true; kick(); return; }
    const ps = shownSet(prev), ns = shownSet(T);
    for (const c of [...tw.cells.keys()]) if (!ns.vis(c)) tw.cells.delete(c);
    const added = [];
    for (const c of M.cells) if (ns.vis(c) && !ps.vis(c)) added.push(c);
    const pd = T.buildStyle === "partdrop", pdFall = pd && !T.grey; // parts reached drop even when the next check is stuck
    if (added.length && prev.built < 1 + 1e-9) {
      if (pd) { if (pdFall) partDrop(added, ns, now); else for (const c of added) gh.delete(c); }
      else if (T.buildStyle === "drop") {
        added.sort((p, q) => p.dropQ - q.dropQ);
        const n = added.length, win = Math.min(480, 120 + n * 0.6);
        added.forEach((c, i) => tw.cells.set(c, { t0: now + (n > 1 ? (i / (n - 1)) * win : 0), dur: 220, h: 1.5, grp: gid++ }));
      } else { const g = gid++; for (const c of added) tw.cells.set(c, { t0: now, dur: 150, h: 1, grp: g }); }
    }
    if (pd) syncGhost(ns, now, !T.grey); else gh.clear();
    let after = added.length ? (T.buildStyle === "drop" ? 600 : 150) : 0;
    if (pd) { let end = now; for (const t of tw.cells.values()) end = Math.max(end, t.t0 + t.dur); after = end > now ? end - now + PD.beat * pace : 0; }
    const pc = padCell(), padNow = T.gen && (!pc || ns.vis(pc)), padWas = prev.gen && (!pc || ps.vis(pc));
    // partdrop: the pad drops onto the grass block under it, a frame after that block starts
    if (padNow && !padWas) tw.pad = !pd ? { t0: now, dur: 150, h: 1, grp: gid++ } : pdFall ? { t0: (pc && tw.cells.has(pc) ? tw.cells.get(pc).t0 + PD.gap : now), dur: PD.dur, h: PD.h, grp: gid++, snap: true } : null;
    if (!padNow) tw.pad = null;
    const bedNow = T.bed && T.built >= 1, bedWas = prev.bed && prev.built >= 1;
    // partdrop: the bed drops last, `beat` after the last block has landed
    if (bedNow && !bedWas) tw.bed = !pd ? { t0: now + after, dur: 150, h: 1, grp: gid++ } : pdFall ? { t0: now + after, dur: PD.dur * pace, h: PD.h, grp: gid++, snap: true } : null;
    if (!bedNow) tw.bed = null;
    const iN = padNow ? T.ingots : 0, iP = padWas ? prev.ingots : 0;
    if (iN > iP) for (let i = iP; i < iN; i++) tw.ingots[i] = { t0: now + (i - iP) * 90, dur: 130, h: 1, grp: gid++ };
    if (iN < iP) tw.ingots.length = iN;
    contentDirty = true;
    kick();
  }

  let pace = 1;
  const api = {
    set(next = {}) {
      const prev = { ...T };
      for (const key of Object.keys(T)) if (key in next) T[key] = next[key];
      T.built = Math.max(0, Math.min(1, +T.built || 0));
      T.ingots = Math.max(0, Math.min(10, Math.round(+T.ingots || 0)));
      if ("wool" in next) woolRgb = T.wool == null ? null : hx(TEAM_WOOL[T.wool] ? teamWool(T.wool) : woolFor(T.wool));
      // a new style shows its state at once; under partdrop the next change animates (the mount's first set() with a
      // style used to leave the second one instant too, which would pop the first check's part)
      if (next.buildStyle && next.buildStyle !== prev.buildStyle) { clearTweens(); gh.clear(); if (T.buildStyle === "partdrop") { syncGhost(shownSet(T), performance.now(), false); shown = true; } contentDirty = true; kick(); return api; }
      applyTarget(shown ? prev : null);
      shown = true;
      return api;
    },
    // partdrop's tempo: 1 is the setup build; < 1 replays it faster (the open build, 2026-10-05)
    pace(x) { pace = Math.max(0.1, +x || 1); return api; },
    options(o = {}) {
      const old = { ...O };
      Object.assign(O, o);
      if (O.edges !== old.edges) { makeRenderer(); dirty = true; }
      if (O.light !== old.light) contentDirty = true;
      if (O.motion !== old.motion) { halt(); startMotion(); }
      else if (O.rest !== old.rest && !spin) settle(true); // spin keeps turning; step and drag move to the new rest
      kick();
      return api;
    },
    // the largest whole scale that fits the box; maxFraction caps the island's height as a share of the box
    fit(wCss, hCss, maxFraction = 1) {
      fitArgs = [wCss, hCss, maxFraction];
      const dpr = window.devicePixelRatio || 1;
      for (const aa of [6, 4, 2]) {
        const b = boxFor(aa), kk = Math.floor(Math.min((wCss * dpr) / (b.maxX - b.minX), (hCss * maxFraction * dpr) / (b.maxY - b.minY)));
        if (kk >= 1 || aa === 2) { a = aa; k = Math.max(1, Math.min(8, kk)); box = b; break; }
      }
      sizeCanvas(); dirty = true; kick();
      return { k, a, w: ((box.maxX - box.minX) * k) / dpr, h: ((box.maxY - box.minY) * k) / dpr };
    },
    setActive(on) {
      active = !!on;
      if (!active) halt(); else startMotion();
      return api;
    },
    frames: () => frames,
    // resolves after the component has drawn its first frame (a page can show its window then). Frames are drawn
    // on requestAnimationFrame, so this waits while the page is hidden; it never resolves after destroy().
    firstFrame() { return drawnOnce ? Promise.resolve() : new Promise((r) => firstWaiters.push(r)); },
    // ms from now until the build in flight has landed, the bed included (0 when nothing falls): a page can hold its
    // "done" moment for the bed (the prototype's servers.js: Play comes with it)
    buildEnd() {
      const now = performance.now();
      let end = now;
      const one = (t) => { if (t) end = Math.max(end, t.t0 + t.dur); };
      for (const t of tw.cells.values()) one(t);
      one(tw.bed); one(tw.pad); tw.ingots.forEach(one);
      return Math.max(0, Math.ceil(end - now));
    },
    destroy() {
      if (destroyed) return;
      destroyed = true; halt(); if (raf) cancelAnimationFrame(raf);
      document.removeEventListener("visibilitychange", onVis);
      const rq = reduceMq();
      rq.removeEventListener && rq.removeEventListener("change", onReduce);
      if (dprMq) dprMq.removeEventListener("change", onDpr);
      ro.disconnect(); geo.dispose(); mat.dispose(); blitGeo.dispose(); blitMat.dispose(); if (rt) rt.dispose(); if (rtMS) rtMS.dispose(); renderer.dispose(); canvas.remove();
    },
  };
  try {
    makeRenderer();
  } catch (e) {
    // Note: three 0.181's own catch shadows its error() logger, so a failed context surfaces here as
    // "TypeError: error is not a function" rather than "Error creating WebGL context."; either way there is no renderer.
    console.warn("island3d: cannot render (no WebGL2?); mount() returns null", e);
    if (ro) ro.disconnect();
    if (renderer) renderer.dispose();
    if (canvas) canvas.remove();
    geo.dispose(); mat.dispose(); blitGeo.dispose(); blitMat.dispose();
    return null;
  }
  document.addEventListener("visibilitychange", onVis);
  const rq = reduceMq();
  rq.addEventListener && rq.addEventListener("change", onReduce);
  watchDpr();
  contentDirty = true;
  kick();
  startMotion();
  return api;
}

// the art box (art px about the pivot) for a block size a = 6 / 4 / 2: lets a page size a slot to an exact scale
export const boxSize = (aa = 6) => { const b = boxFor(aa); return { w: b.maxX - b.minX, h: b.maxY - b.minY }; };
// the canvas's box (every yaw, plus a block of headroom) and the island as drawn at one yaw (its blocks only), both in
// art px about the same pivot: a page can place what is actually visible at rest (1.5: equal margins on home)
export const sweptBox = (aa = 6) => ({ ...boxFor(aa) });
const RESTS = {};
export function restBox(aa = 6, yaw = 0) {
  const key = aa + ":" + yaw;
  if (!RESTS[key]) RESTS[key] = bounds(model().cells.filter((c) => c.kind !== "fill").map((c) => ({ x0: c.x, y0: c.y, z0: c.z, x1: c.x + 1, y1: c.y + 1, z1: c.z + 1 })), aa, [yaw]);
  return { ...RESTS[key] };
}
