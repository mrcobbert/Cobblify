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

// One Minecraft block, in world units. Hand-tuned so the island fills the
// window's height at every angle it turns through. The frustum is 5.9 units
// tall; the model reads as ~43 blocks tall once the isometric tilt folds its
// 24-block footprint into the silhouette.
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

/**
 * @param {HTMLCanvasElement} canvas
 * @returns {{ setMood(name: string): void, dispose(): void }|null} null if WebGL is unavailable
 */
export function createHeroScene(canvas) {
  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({
      canvas,
      antialias: true,
      alpha: true,
      powerPreference: "low-power",
    });
  } catch {
    return null;
  }

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
  const VIEW = 2.95; // half-height of the frustum, in world units
  const camera = new THREE.OrthographicCamera(-VIEW, VIEW, VIEW, -VIEW, 0.1, 60);
  camera.position.set(9, 7.4, 9);
  camera.lookAt(0, 0, 0); // the extractor centres the model on the origin

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
  keyLight.shadow.mapSize.set(2048, 2048);
  keyLight.shadow.radius = 2;
  keyLight.shadow.camera.near = 8.3;
  keyLight.shadow.camera.far = 15.1;
  keyLight.shadow.camera.left = -2.5;
  keyLight.shadow.camera.right = 2.5;
  keyLight.shadow.camera.top = 3.25;
  keyLight.shadow.camera.bottom = -3.25;
  // Small: with the cubes touching, an oversized bias leaks light at contact
  // edges instead of hiding inside the gap that used to be there.
  keyLight.shadow.bias = -0.0002;
  // In world units, so it has to shrink with the texel: 0.01 was ~1 texel at
  // the old density and would be 4 at this one, eating contact shadow.
  keyLight.shadow.normalBias = 0.0025;
  scene.add(keyLight);

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

  function frame() {
    world.rotation.y = t * 0.11;
    world.position.y = Math.sin(t * 0.62) * 0.07;
    world.rotation.z = Math.sin(t * 0.37) * 0.011;
    bedGlow.material.opacity = 0.32 + Math.sin(t * 1.1) * 0.05;
    renderer.render(scene, camera);
  }

  function tick() {
    t += Math.min(clock.getDelta(), 0.05);
    frame();
  }

  function start() {
    if (running || motion.matches || document.hidden) return;
    running = true;
    clock.getDelta(); // drop the paused interval
    renderer.setAnimationLoop(tick);
  }

  function stop() {
    running = false;
    renderer.setAnimationLoop(null);
  }

  function resize() {
    const w = canvas.clientWidth || 1;
    const h = canvas.clientHeight || 1;
    const aspect = w / h;
    camera.left = -VIEW * aspect;
    camera.right = VIEW * aspect;
    camera.top = VIEW;
    camera.bottom = -VIEW;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);
    frame();
  }

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
    dispose() {
      stop();
      observer.disconnect();
      document.removeEventListener("visibilitychange", onVisibility);
      motion.removeEventListener("change", onMotion);
      renderer.dispose();
    },
  };
}
