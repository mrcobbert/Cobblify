import test from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";
import { readFileSync } from "node:fs";

// `ui/island3d.js` imports `island.json`, which Node loads only with an import
// attribute; the harness's resolve hook adds it (see css-stub-loader.mjs).
register(new URL("./test-support/css-stub-loader.mjs", import.meta.url));

// Imported before any window/document global exists: the module's top level
// must load under plain Node (reduced motion and matchMedia are looked up on
// first use, not at import).
assert.equal(typeof globalThis.window, "undefined");
const island3d = await import("./ui/island3d.js");
const { mount, woolFor, teamWool, TEAM_WOOL, boxSize, sweptBox, restBox, buildParts } = island3d;

const island = JSON.parse(readFileSync(new URL("./island.json", import.meta.url), "utf8"));

test("the module exports the component's surface and nothing of the prototype's assembly API", () => {
  for (const name of ["mount", "woolFor", "teamWool", "TEAM_WOOL", "boxSize", "sweptBox", "restBox", "buildParts"]) {
    assert.ok(name in island3d, `${name} is exported`);
  }
  for (const name of ["registerAssembly", "assemblies"]) assert.ok(!(name in island3d), `${name} is gone`);
});

test("boxSize, sweptBox and restBox match the prototype (three r128 build, kit/island3d.js 1.6)", () => {
  // Expected values computed by running the prototype's own functions in Node.
  assert.deepEqual(boxSize(6), { w: 236, h: 242 });
  assert.deepEqual(boxSize(4), { w: 158, h: 162 });
  assert.deepEqual(boxSize(2), { w: 80, h: 82 });
  assert.deepEqual(boxSize(), boxSize(6));
  assert.deepEqual(sweptBox(6), { minX: -118, maxX: 118, minY: -124, maxY: 118 });
  assert.deepEqual(restBox(6, (270 * Math.PI) / 180), { minX: -90, maxX: 90, minY: -117, maxY: 114 });
  assert.deepEqual(restBox(6, 0), { minX: -90, maxX: 114, minY: -105, maxY: 102 });
});

test("woolFor and teamWool give the prototype's colours", () => {
  const wool = {
    "#C0DCFF": "#68a8f4",
    "#ffffff": "#d0d0d0",
    "#808080": "#696969",
    "#202020": "#696969",
    "#ff5470": "#ff5470",
    "#2ff0d4": "#00c2a8",
    "#ffc247": "#d49900",
    "#7a5cff": "#7a5cff",
    "#b0b0b8": "#8d8d8d",
  };
  for (const [hex, want] of Object.entries(wool)) assert.equal(woolFor(hex), want, `woolFor(${hex})`);
  const team = {
    Red: "#963430", Blue: "#2e388d", Green: "#41ae38", Yellow: "#b1a627",
    Aqua: "#2e6e89", White: "#d0d0d0", Pink: "#d08499", Gray: "#696969",
  };
  for (const [name, want] of Object.entries(team)) assert.equal(teamWool(name), want, `teamWool(${name})`);
  assert.equal(teamWool("Purple"), null);
  assert.deepEqual(Object.keys(TEAM_WOOL), Object.keys(team));
});

test("the build parts cover every voxel of island.json exactly once", () => {
  const parts = buildParts();
  assert.equal(parts.length, 6);
  for (const [i, p] of parts.entries()) assert.ok(p.length > 0, `part ${i + 1} is not empty`);

  const key = (x, y, z) => `${x},${y},${z}`;
  const voxels = new Map();
  for (const [list, kind] of [[island.solid, "solid"], [island.glow, "glow"]]) {
    for (let i = 0; i < list.length; i += 4) {
      const k = key(list[i], list[i + 1], list[i + 2]);
      assert.ok(!voxels.has(k), `island.json lists ${k} once`);
      voxels.set(k, { ci: list[i + 3], kind });
    }
  }

  const seen = new Map();
  for (const [i, p] of parts.entries()) {
    for (const c of p) {
      const k = key(c.x, c.y, c.z);
      assert.ok(!seen.has(k), `${k} is in one part only (also in part ${seen.get(k)})`);
      seen.set(k, i + 1);
      if (c.kind === "fill") {
        // interior rock the model adds under the surface: never a voxel of island.json, always part 1 (rock)
        assert.ok(!voxels.has(k), `fill ${k} is not a surface voxel`);
        assert.equal(i + 1, 1);
      } else {
        assert.deepEqual({ ci: c.ci, kind: c.kind }, voxels.get(k), `${k} keeps its palette index and kind`);
      }
    }
  }
  for (const k of voxels.keys()) assert.ok(seen.has(k), `voxel ${k} is in a part`);
});

test("mount answers null in a page without WebGL, and leaves the host empty", async () => {
  const { JSDOM, VirtualConsole } = await import("jsdom");
  // jsdom has no canvas backend: getContext() answers null, so three cannot make a WebGL2 context
  const dom = new JSDOM("<!doctype html><div id=host></div>", { pretendToBeVisual: true, virtualConsole: new VirtualConsole() });
  const g = globalThis;
  const saved = { window: g.window, document: g.document };
  g.window = dom.window;
  g.document = dom.window.document;
  const logged = [];
  const real = { error: console.error, warn: console.warn };
  console.error = (...args) => logged.push(args);
  console.warn = (...args) => logged.push(args);
  const added = [];
  const realAdd = dom.window.document.addEventListener.bind(dom.window.document);
  dom.window.document.addEventListener = (type, ...rest) => { added.push(type); return realAdd(type, ...rest); };
  let isl;
  try {
    const host = dom.window.document.getElementById("host");
    isl = mount(host, { edges: "art", rest: "quarter", motion: "drag", light: "smooth", artAA: false, yaw0: 270 });
    assert.equal(isl, null, "no WebGL: mount() returns null, the documented contract");
    assert.equal(host.childNodes.length, 0, "nothing is left in the host");
    assert.deepEqual(added, [], "no document listener is left behind");
    assert.ok(logged.length > 0, "the failure is reported on the console");
  } finally {
    console.error = real.error;
    console.warn = real.warn;
    g.window = saved.window;
    g.document = saved.document;
    dom.window.close();
  }
});
