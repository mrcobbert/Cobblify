import test from "node:test";
import assert from "node:assert/strict";
import { register } from "node:module";

// `scene.js` imports `island.json`, which Node loads only with an import
// attribute; the harness's resolve hook adds it (see css-stub-loader.mjs).
register(new URL("./test-support/css-stub-loader.mjs", import.meta.url));

// The hero scene reads `window.devicePixelRatio` while wiring the renderer.
// This file owns its process, so the stubs stay for its lifetime.
Object.defineProperty(globalThis, "window", {
  value: { devicePixelRatio: 1, matchMedia: () => ({ matches: false, addEventListener() {} }) },
  configurable: true,
  writable: true,
});

const { createHeroScene } = await import("./scene.js");

test("a renderer that fails AFTER construction leaves the hero scene null (J7)", () => {
  // The window blacks out when an exception escapes the scene boot: the
  // stage is marked `lit` and the entrance runs over a canvas that never
  // rendered. A missing WebGL context was already handled; what escaped was
  // every line after the constructor, of which setPixelRatio is the first.
  let built = 0;
  class LateFailingRenderer {
    constructor() {
      built += 1;
    }
    setPixelRatio() {
      throw new Error("context lost");
    }
  }

  const errors = [];
  const realError = console.error;
  console.error = (...args) => errors.push(args);
  let scene;
  try {
    scene = createHeroScene({}, { Renderer: LateFailingRenderer });
  } finally {
    console.error = realError;
  }

  assert.equal(built, 1, "the injected renderer is the one the scene builds");
  assert.equal(scene, null, "a failed boot answers null, the documented contract");
  assert.ok(
    errors.some((args) => args.some((a) => a instanceof Error && a.message === "context lost")),
    "the exception reached console.error instead of escaping silently",
  );
});
