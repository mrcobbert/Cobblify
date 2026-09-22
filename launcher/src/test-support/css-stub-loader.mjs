/**
 * Module resolve hook for the jsdom window harness.
 *
 * `main.js` is written for Vite: it imports stylesheets (`@fontsource/...css`,
 * `./style.css`) that Vite turns into CSS assets and Node cannot load at all,
 * and `scene.js` imports `island.json`, which Node loads only with an explicit
 * import attribute. Both are build-tool concerns, not launcher behaviour, so
 * the hook answers a stylesheet with an empty module and re-labels a JSON
 * resolution instead of asking the sources to carry test-only syntax.
 *
 * Registered with `node:module`'s `register()` - see window-harness.js and
 * scene.test.js.
 */
const EMPTY_MODULE = "data:text/javascript,export%20default%20{}";

export async function resolve(specifier, context, nextResolve) {
  if (specifier.endsWith(".css")) {
    return { url: EMPTY_MODULE, shortCircuit: true };
  }
  const resolved = await nextResolve(specifier, context);
  if (resolved.url.startsWith("file:") && resolved.url.endsWith(".json")) {
    return { ...resolved, importAttributes: { type: "json" } };
  }
  return resolved;
}
