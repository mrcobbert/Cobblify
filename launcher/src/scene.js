import * as THREE from "three";
import island from "./island.json";

/**
 * The hero: a Bedwars team island rendered as voxel art.
 *
 * The island is real map geometry, not an invention - island.json is one team
 * island lifted block-for-block out of a Bedwars world, keeping only the
 * surface blocks, since nothing inside a solid mass is ever visible. See
 * analysis/map-voxel-research/extract-island.py for the extractor.
 *
 * Everything is baked into InstancedMeshes - lit, emissive, and the bed, which
 * is modelled finer than a block - so the whole island costs three draw calls.
 */

// One Minecraft block, in world units. Homepage framing measures the rotating
// silhouette and pins it to --page-inset; VIEW below is only the dash corner.
const CELL = 0.13;
// Cubes meet exactly. They used to be inset slightly to leave a seam, but a
// real island is one block thick almost everywhere - 82% of it is thin along
// some axis - so that seam stopped being a seam and became a hole with the
// page showing through it. The grain now comes from seamTexture() instead.
const VOXEL = CELL;

const MOOD = {
  ready: { rim: 0x2ff0d4 },
  blocked: { rim: 0xffc247 },
  error: { rim: 0xff5470 },
};

/** Deterministic value hash. Same island on every launch. */
function rand(x, y, z) {
  const s = Math.sin(x * 127.1 + y * 311.7 + z * 74.7) * 43758.5453;
  return s - Math.floor(s);
}

const _a = new THREE.Color();

/** Per-voxel lightness jitter, so flat faces read as material rather than paint. */
function jitter(hex, x, y, z, amount = 0.055) {
  return _a
    .setHex(hex)
    .offsetHSL(0, 0, (rand(x, y, z) - 0.5) * amount)
    .getHex();
}

/**
 * Reads the exported island into voxel lists.
 * Returns { solid, glow } arrays of { x, y, z, c } in block coordinates,
 * already centred on the origin by the extractor.
 */
function build() {
  const palette = island.palette.map((hex) => parseInt(hex.slice(1), 16));

  // Emissive voxels keep their exact colour - jitter would read as flicker on
  // something that is meant to be a light source.
  const read = (packed, shade) => {
    const out = [];
    for (let i = 0; i < packed.length; i += 4) {
      const x = packed[i];
      const y = packed[i + 1];
      const z = packed[i + 2];
      const c = palette[packed[i + 3]];
      out.push({ x, y, z, c: shade ? jitter(c, x, y, z) : c });
    }
    return out;
  };

  return { solid: read(island.solid, true), glow: read(island.glow, false) };
}

/** `unit` is the world size of one coordinate step, so the bed can come in at
 *  a third of a block without needing its own placement code. */
function instanced(voxels, material, geometry, unit = CELL) {
  const mesh = new THREE.InstancedMesh(geometry, material, voxels.length);
  const m = new THREE.Matrix4();
  const c = new THREE.Color();
  for (let i = 0; i < voxels.length; i++) {
    const v = voxels[i];
    m.setPosition(v.x * unit, v.y * unit, v.z * unit);
    mesh.setMatrixAt(i, m);
    mesh.setColorAt(i, c.setHex(v.c));
  }
  mesh.instanceMatrix.needsUpdate = true;
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  return mesh;
}

/**
 * White tile with a darker one-texel rim. Draws the voxel seam as shading
 * inside each face instead of a gap between faces, so the grain survives
 * without anywhere for the page to show through. Multiplies with the
 * per-instance colour, so the palette and the jitter both still apply.
 */
// The tile is far finer than one screen pixel on purpose. At 16 texels the rim
// landed at ~1.7 device px and sat in magnification, so NearestFilter snapped
// it to pixel boundaries on every face at once as the island turned - which is
// most of what read as "wiggly". Oversampled, it stays in minification and the
// mip chain resolves it smoothly. RIM is unchanged at CELL/16.
const SEAM_TEXELS = 64;
const SEAM_RIM = 4;
const RIM = (CELL * SEAM_RIM) / SEAM_TEXELS; // border thickness, in world units

function seamTexture() {
  const n = SEAM_TEXELS;
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = n;
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#ffffff";
  ctx.fillRect(0, 0, n, n);
  ctx.fillStyle = "#b4b4b4"; // matches the contrast the old gap had
  ctx.fillRect(0, 0, n, SEAM_RIM);
  ctx.fillRect(0, n - SEAM_RIM, n, SEAM_RIM);
  ctx.fillRect(0, 0, SEAM_RIM, n);
  ctx.fillRect(n - SEAM_RIM, 0, SEAM_RIM, n);
  const texture = new THREE.CanvasTexture(canvas);
  texture.magFilter = THREE.NearestFilter;
  texture.minFilter = THREE.LinearMipmapLinearFilter;
  texture.anisotropy = 4;
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

/**
 * Rescales a BoxGeometry's UVs so the seam texture's one-texel rim comes out
 * RIM world units thick on every face, however oblong the box is.
 *
 * On a plain [0,1] mapping the rim is always a sixteenth of the face, so the
 * bed's long side would get a border six times heavier than the island's
 * blocks. Solving `RIM = a(1/n - t)/(1 - 2t)` for the inset `t` fixes the
 * border in world units instead. A negative `t` samples past the edge, which
 * clamping turns into a wider rim - correct for faces thinner than n*RIM.
 */
function fitSeam(geometry, w, h, d) {
  const faces = [d, h, d, h, w, d, w, d, w, h, w, h]; // +x -x +y -y +z -z, (u,v) each
  const uv = geometry.attributes.uv;
  for (let f = 0; f < 6; f++) {
    const inset = (a) =>
      Math.abs(a - 2 * RIM) < 1e-9
        ? 0
        : ((a * SEAM_RIM) / SEAM_TEXELS - RIM) / (a - 2 * RIM);
    const tu = inset(faces[f * 2]);
    const tv = inset(faces[f * 2 + 1]);
    for (let i = f * 4; i < f * 4 + 4; i++) {
      uv.setXY(i, tu + uv.getX(i) * (1 - 2 * tu), tv + uv.getY(i) * (1 - 2 * tv));
    }
  }
  uv.needsUpdate = true;
}

/** Radial falloff used for the additive glows. */
function glowTexture() {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = 128;
  const ctx = canvas.getContext("2d");
  const g = ctx.createRadialGradient(64, 64, 0, 64, 64, 64);
  g.addColorStop(0, "rgba(255,255,255,0.95)");
  g.addColorStop(0.3, "rgba(255,255,255,0.32)");
  g.addColorStop(1, "rgba(255,255,255,0)");
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 128, 128);
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

const BOB = 0.07;

// The key light's shadow frustum, in world units, fitted to what the island
// sweeps through as it turns. The x span is the one the map is sized against;
// y is 1.3x coarser at the same map size, so it, not x, sets the worst texel.
const SHADOW_SPAN_X = 5.0;
const SHADOW_SPAN_Y = 6.5;
// Target device pixels per shadow texel. The map is resized to hold this as the
// island's size on screen changes, instead of costing a fixed 2048x2048 whether
// it fills the window or sits in the dash corner at 190x120 - where the texel
// was landing at a tenth of a device pixel, nine times finer per axis than
// anything downstream can resolve. Held constant, `radius` stays right too: it
// counts texels, so the penumbra measured in screen pixels does not move.
const SHADOW_TEXEL_PX = 0.8;
const SHADOW_MIN = 256;
const SHADOW_MAX = 2048;
// normalBias is in world units and is meant to be about one texel, so it tracks
// the texel rather than staying pinned to what the 2048 map wanted. (`bias` is
// in normalised depth and does not scale with the map, so it is left alone.)
const NORMAL_BIAS_TEXELS = 1.024;
// The island turns once every 57 s and bobs on a 10 s sine, so the loop does not
// need the display's refresh rate - on a 144 Hz panel it was paying 2.4x for
// motion nothing can see. Not 30 on the homepage: there the outermost voxel
// steps 1.7-2.3 device px per frame at 30, more than the shadow-texel jump the
// big map exists to hide, so the silhouette itself would start stepping. In the
// dash corner that same voxel moves 0.25 px per frame, so 30 is free there.
const FPS_HOME = 60;
const FPS_DASH = 30;

/** World-space points that define the island silhouette: voxel centres + bed corners. */
function islandPoints() {
  const pts = [];
  const pack = (src) => {
    for (let i = 0; i < src.length; i += 4) {
      pts.push(src[i] * CELL, src[i + 1] * CELL, src[i + 2] * CELL);
    }
  };
  pack(island.solid);
  pack(island.glow);
  const bedUnit = CELL / island.bedScale;
  for (const box of island.bedBoxes) {
    const cx = (box.min[0] + (box.size[0] - 1) / 2) * bedUnit;
    const cy = (box.min[1] + (box.size[1] - 1) / 2) * bedUnit;
    const cz = (box.min[2] + (box.size[2] - 1) / 2) * bedUnit;
    const hw = (box.size[0] * bedUnit) / 2;
    const hh = (box.size[1] * bedUnit) / 2;
    const hd = (box.size[2] * bedUnit) / 2;
    for (const sx of [-1, 1]) {
      for (const sy of [-1, 1]) {
        for (const sz of [-1, 1]) {
          pts.push(cx + sx * hw, cy + sy * hh, cz + sz * hd);
        }
      }
    }
  }
  return pts;
}

function pageInsetPx(el) {
  const raw = getComputedStyle(el).getPropertyValue("--page-inset");
  const n = parseFloat(raw);
  return Number.isFinite(n) ? n : 52;
}

/**
 * @param {HTMLCanvasElement} canvas
 * @param {{ Renderer?: typeof THREE.WebGLRenderer }} [opts] the renderer class,
 *   injectable so a test can fail one the way a real driver does
 * @returns {{ setMood(name: string): void, relayout(): void, dispose(): void }|null} null if the scene cannot boot
 */
export function createHeroScene(canvas, { Renderer = THREE.WebGLRenderer } = {}) {
  // The boot used to guard only the context creation, so anything that threw
  // AFTER it - a context lost at setPixelRatio, a shader compile, a bad
  // uniform - escaped module evaluation and took the whole window with it:
  // main.js has already marked the stage `lit` by then. The hero is
  // decoration; the launcher is not. Report it and carry on without it.
  try {
    return buildHeroScene(canvas, Renderer);
  } catch (e) {
    console.error(e);
    return null;
  }
}

function buildHeroScene(canvas, Renderer) {
  const renderer = new Renderer({
    canvas,
    antialias: true,
    alpha: true,
    powerPreference: "low-power",
  });

  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearAlpha(0);
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0; // closing the seams removed 13.5% dark area
  renderer.shadowMap.enabled = true;
  // Not PCF_SOFT: despite the name, that is the narrower 9-tap filter with a
  // fixed footprint which ignores shadow.radius. PCF is the 17-tap tunable one,
  // and a wide radius is what stops shadow edges stepping in unison as the
  // island turns - measured to halve the largest synchronously-jumping region.
  renderer.shadowMap.type = THREE.PCFShadowMap;

  const scene = new THREE.Scene();

  // Orthographic: a true isometric read, and nothing warps as the window resizes.
  const VIEW = 2.95; // dash corner only: half-height of the frustum, in world units
  const camera = new THREE.OrthographicCamera(-VIEW, VIEW, VIEW, -VIEW, 0.1, 60);
  camera.position.set(9, 7.4, 9);
  camera.lookAt(0, 0, 0); // the extractor centres the model on the origin
  camera.updateMatrixWorld(true);

  const points = islandPoints();
  const inv = camera.matrixWorldInverse.elements;
  const axes = camera.matrixWorld.elements;
  const cornerPadX =
    (CELL / 2) * (Math.abs(axes[0]) + Math.abs(axes[1]) + Math.abs(axes[2]));
  const cornerPadY =
    (CELL / 2) * (Math.abs(axes[4]) + Math.abs(axes[5]) + Math.abs(axes[6]));

  function measure(yaw) {
    const cos = Math.cos(yaw);
    const sin = Math.sin(yaw);
    let minX = Infinity;
    let maxX = -Infinity;
    let minY = Infinity;
    let maxY = -Infinity;
    for (let i = 0; i < points.length; i += 3) {
      const x = points[i] * cos + points[i + 2] * sin;
      const y = points[i + 1];
      const z = -points[i] * sin + points[i + 2] * cos;
      const vx = inv[0] * x + inv[4] * y + inv[8] * z + inv[12];
      const vy = inv[1] * x + inv[5] * y + inv[9] * z + inv[13];
      if (vx < minX) minX = vx;
      if (vx > maxX) maxX = vx;
      if (vy < minY) minY = vy;
      if (vy > maxY) maxY = vy;
    }
    return {
      minX: minX - cornerPadX,
      maxX: maxX + cornerPadX,
      minY: minY - cornerPadY,
      maxY: maxY + cornerPadY,
    };
  }

  let sweepW = 0;
  let sweepH = 0;
  let sweepMaxX = -Infinity;
  for (let i = 0; i < 48; i++) {
    const b = measure((i / 48) * Math.PI * 2);
    sweepW = Math.max(sweepW, b.maxX - b.minX);
    sweepH = Math.max(sweepH, b.maxY - b.minY);
    sweepMaxX = Math.max(sweepMaxX, b.maxX);
  }
  sweepH += 2 * BOB;

  const world = new THREE.Group();
  scene.add(world);

  const geometry = new THREE.BoxGeometry(VOXEL, VOXEL, VOXEL);
  const { solid, glow } = build();
  const seam = seamTexture();

  const solidMesh = instanced(
    solid,
    new THREE.MeshStandardMaterial({ map: seam, roughness: 0.82, metalness: 0.02 }),
    geometry,
  );
  solidMesh.castShadow = true;
  solidMesh.receiveShadow = true;
  world.add(solidMesh);

  // The bed is one object, so it is three boxes - frame, mattress, pillow -
  // rather than a pile of little cubes. Cut into cubes it would pick up an
  // interior grid that no bed in the game has; as boxes, the only edges drawn
  // are the bed's own.
  const bedUnit = CELL / island.bedScale;
  const palette = island.palette.map((hex) => parseInt(hex.slice(1), 16));
  for (const box of island.bedBoxes) {
    const [w, h, d] = box.size.map((n) => n * bedUnit);
    const geometry = new THREE.BoxGeometry(w, h, d);
    fitSeam(geometry, w, h, d);
    const mesh = new THREE.Mesh(
      geometry,
      new THREE.MeshStandardMaterial({
        map: seam,
        color: palette[box.c],
        roughness: 0.7,
        metalness: 0.02,
      }),
    );
    mesh.position.set(
      ...box.min.map((n, i) => (n + (box.size[i] - 1) / 2) * bedUnit),
    );
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    world.add(mesh);
  }

  // A given island may have no light-emitting blocks at all.
  if (glow.length) {
    world.add(
      instanced(glow, new THREE.MeshBasicMaterial({ toneMapped: false }), geometry),
    );
  }

  // ── light ─────────────────────────────────────────────────────────────────
  scene.add(new THREE.HemisphereLight(0x5c7ea6, 0x080b12, 0.85));

  const keyLight = new THREE.DirectionalLight(0xfff0dc, 2.1);
  keyLight.position.set(6, 9, 4.5);
  keyLight.castShadow = true;
  // The island turns while the light stays put, so a shadow edge drifts a
  // fraction of a shadow texel per frame: it stalls, then jumps a whole texel.
  // Nothing removes that quantisation, so the fix is to make the texel small
  // enough that the jump lands under one screen pixel and antialiasing eats it.
  // Fitting the camera to what the island actually sweeps through (verified
  // against its rotated bounds, it cannot clip) and doubling the map takes the
  // jump from 2.05 screen px to 0.51.
  // Starting size only - applyFrame() sizes the map to the view from here on.
  keyLight.shadow.mapSize.set(SHADOW_MAX, SHADOW_MAX);
  keyLight.shadow.radius = 2;
  keyLight.shadow.camera.near = 8.3;
  keyLight.shadow.camera.far = 15.1;
  keyLight.shadow.camera.left = -SHADOW_SPAN_X / 2;
  keyLight.shadow.camera.right = SHADOW_SPAN_X / 2;
  keyLight.shadow.camera.top = SHADOW_SPAN_Y / 2;
  keyLight.shadow.camera.bottom = -SHADOW_SPAN_Y / 2;
  // Small: with the cubes touching, an oversized bias leaks light at contact
  // edges instead of hiding inside the gap that used to be there.
  keyLight.shadow.bias = -0.0002;
  // In world units, so it has to shrink with the texel: 0.01 was ~1 texel at
  // the old density and would be 4 at this one, eating contact shadow. One
  // expression so it cannot drift out of step with the map size.
  keyLight.shadow.normalBias =
    (SHADOW_SPAN_X / SHADOW_MAX) * NORMAL_BIAS_TEXELS;
  scene.add(keyLight);

  /**
   * Sizes the shadow map so one texel lands at about SHADOW_TEXEL_PX device
   * pixels, given how many screen pixels one world unit currently covers.
   *
   * Powers of two so a smooth resize steps through a handful of reallocations
   * rather than one per frame.
   */
  function setShadowResolution(pxPerWorld) {
    const want =
      (SHADOW_SPAN_X * pxPerWorld * renderer.getPixelRatio()) / SHADOW_TEXEL_PX;
    const n = Math.min(
      SHADOW_MAX,
      Math.max(SHADOW_MIN, 2 ** Math.ceil(Math.log2(Math.max(1, want)))),
    );
    if (n === keyLight.shadow.mapSize.x) return;
    keyLight.shadow.mapSize.set(n, n);
    // Three allocates the map only when it is null and never checks the size of
    // one it already holds, so mapSize alone does nothing after the first
    // render. shadow.dispose() is not the way out either - it frees the render
    // target but leaves the reference, which is worse than doing nothing. Drop
    // it by hand, or leak the texture and its framebuffer on every change.
    keyLight.shadow.map?.dispose();
    keyLight.shadow.map = null;
    keyLight.shadow.normalBias = (SHADOW_SPAN_X / n) * NORMAL_BIAS_TEXELS;
  }

  // Rim from behind - this is the light that carries the state colour.
  const rim = new THREE.DirectionalLight(MOOD.ready.rim, 1.15);
  rim.position.set(-7, 2.5, -6);
  scene.add(rim);

  // Cold bounce into the shadowed underside.
  const bounce = new THREE.DirectionalLight(0x3f6cff, 0.5);
  bounce.position.set(2, -6, 5);
  scene.add(bounce);

  // ── bed bloom ─────────────────────────────────────────────────────────────
  const texture = glowTexture();

  // Warm bloom on the bed itself. Always red: this is the thing you look at.
  const bedGlow = new THREE.Sprite(
    new THREE.SpriteMaterial({
      map: texture,
      color: 0xff3b5f,
      blending: THREE.AdditiveBlending,
      depthWrite: false,
      transparent: true,
      opacity: 0.34,
      toneMapped: false,
    }),
  );
  bedGlow.scale.set(1.9, 1.9, 1);
  // Sits on the real bed, and rides the world group so it stays there as the
  // island turns.
  bedGlow.position.set(
    island.bed[0] * CELL,
    island.bed[1] * CELL,
    island.bed[2] * CELL,
  );
  world.add(bedGlow);

  // ── loop ──────────────────────────────────────────────────────────────────
  const clock = new THREE.Clock();
  const motion = window.matchMedia("(prefers-reduced-motion: reduce)");
  let t = 3.4; // opening pose: the bed reads clearly from the start
  let running = false;
  let frameInterval = 1 / FPS_HOME; // applyFrame() sets this per view
  let sinceRender = 0;
  let paintedOnResize = false;

  function frame() {
    world.rotation.y = t * 0.11;
    world.position.y = Math.sin(t * 0.62) * BOB;
    world.rotation.z = Math.sin(t * 0.37) * 0.011;
    bedGlow.material.opacity = 0.32 + Math.sin(t * 1.1) * 0.05;
    renderer.render(scene, camera);
  }

  function applyFrame() {
    const w = canvas.clientWidth || 1;
    const h = canvas.clientHeight || 1;
    const stage = canvas.closest(".stage");
    const view = stage?.dataset.view;
    const layout = stage?.dataset.layout;

    if (view === "dash") {
      const aspect = w / h;
      camera.left = -VIEW * aspect;
      camera.right = VIEW * aspect;
      camera.top = VIEW;
      camera.bottom = -VIEW;
      camera.updateProjectionMatrix();
      frameInterval = 1 / FPS_DASH;
      // Width only widens the world span here; VIEW fixes the scale off height.
      setShadowResolution(h / (2 * VIEW));
      return;
    }

    frameInterval = 1 / FPS_HOME;
    let pxPerWorld;

    if (layout === "stack") {
      const copy = stage?.querySelector(".copy");
      const copyH = copy?.offsetHeight ?? 0;
      const slotH = Math.max(1, h - copyH);
      const pad = 16;
      pxPerWorld = Math.max(
        1e-6,
        Math.min((w - 2 * pad) / sweepW, (slotH - 2 * pad) / sweepH),
      );
      const worldW = w / pxPerWorld;
      const worldH = h / pxPerWorld;
      camera.left = -worldW / 2;
      camera.right = worldW / 2;
      camera.top = ((slotH / 2) / h) * worldH;
      camera.bottom = camera.top - worldH;
    } else {
      const inset = pageInsetPx(stage || document.documentElement);
      const vPad = 16;
      pxPerWorld = Math.max(
        1e-6,
        Math.min((h - 2 * vPad) / sweepH, Math.max(1, w - inset) / sweepW),
      );
      const worldW = w / pxPerWorld;
      const worldH = h / pxPerWorld;
      camera.top = worldH / 2;
      camera.bottom = -worldH / 2;
      camera.right = sweepMaxX + inset / pxPerWorld;
      camera.left = camera.right - worldW;
    }
    camera.updateProjectionMatrix();
    setShadowResolution(pxPerWorld);
  }

  function tick() {
    const dt = Math.min(clock.getDelta(), 0.05);
    // Advances whether or not this frame renders, so the motion stays
    // time-based and capping the rate does not slow the island down.
    t += dt;

    // ResizeObserver callbacks run after the animation callbacks and before the
    // paint, so on any frame where one fires, the render below is overwritten -
    // or thrown away outright, since setSize clears the buffer. That is not a
    // stale frame reaching the screen, it is a whole wasted render, shadow map
    // included. The dash drop animates width and height for 0.45 s, so it was
    // ~27 frames of exactly that. Let the observer be the one that paints.
    if (paintedOnResize) {
      paintedOnResize = false;
      sinceRender = 0;
      return;
    }

    sinceRender += dt;
    if (sinceRender < frameInterval) return;
    // Carry the remainder so the long-run rate is the target rather than the
    // next divisor of the refresh rate; clamp it so a stall cannot bank credit.
    sinceRender = Math.min(sinceRender - frameInterval, frameInterval);
    frame();
  }

  function start() {
    if (running || motion.matches || document.hidden) return;
    running = true;
    clock.getDelta(); // drop the paused interval
    sinceRender = frameInterval; // paint on the first tick, not one interval in
    paintedOnResize = false;
    renderer.setAnimationLoop(tick);
  }

  function stop() {
    running = false;
    renderer.setAnimationLoop(null);
  }

  function resize() {
    const w = Math.max(1, Math.round(canvas.clientWidth));
    const h = Math.max(1, Math.round(canvas.clientHeight));
    if (w !== lastW || h !== lastH) {
      lastW = w;
      lastH = h;
      renderer.setSize(w, h, false);
    }
    applyFrame();
    // Paint before the browser composites. setSize clears the drawing buffer;
    // waiting for the animation loop is the resize flash. This render is the
    // one that survives the frame, so tell the loop to sit the next one out.
    frame();
    paintedOnResize = running;
  }

  let lastW = 0;
  let lastH = 0;

  const observer = new ResizeObserver(resize);
  observer.observe(canvas);
  resize();

  const onVisibility = () => (document.hidden ? stop() : start());
  document.addEventListener("visibilitychange", onVisibility);

  const onMotion = () => (motion.matches ? (stop(), frame()) : start());
  motion.addEventListener("change", onMotion);

  start();
  if (motion.matches) frame(); // one still pose, no loop

  return {
    setMood(name) {
      const mood = MOOD[name];
      if (!mood) return;
      rim.color.setHex(mood.rim);
      if (!running) frame();
    },
    relayout() {
      resize();
    },
    dispose() {
      stop();
      observer.disconnect();
      document.removeEventListener("visibilitychange", onVisibility);
      motion.removeEventListener("change", onMotion);
      // The shadow map belongs to the light, not the renderer, so it does not
      // go with renderer.dispose() - and this scene now reallocates it.
      keyLight.shadow.map?.dispose();
      keyLight.shadow.map = null;
      renderer.dispose();
    },
  };
}
