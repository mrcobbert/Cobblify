/**
 * Module resolve hook for the jsdom window harness.
 *
 * `main.js` is written for Vite: it imports stylesheets (`@fontsource/...css`,
 * `./style.css`) that Vite turns into CSS assets and Node cannot load at all,
 * the redesign's UI imports an image, and `island3d.js` imports `island.json`, which Node loads only with an
 * explicit import attribute. Both are build-tool concerns, not launcher behaviour, so
 * the hook answers a stylesheet with an empty module and re-labels a JSON
 * resolution instead of asking the sources to carry test-only syntax.
 *
 * Registered with `node:module`'s `register()` - see window-harness.js and
 * scene.test.js.
 */
const EMPTY_MODULE = "data:text/javascript,export%20default%20{}";
/** Vite answers an image or font import with its asset URL; a stand-in URL is enough here. */
const ASSET_MODULE = "data:text/javascript,export%20default%20%22asset.bin%22";

export async function resolve(specifier, context, nextResolve) {
  if (specifier.endsWith(".css")) {
    return { url: EMPTY_MODULE, shortCircuit: true };
  }
  if (/\.(png|otf|woff2?)$/.test(specifier)) {
    return { url: ASSET_MODULE, shortCircuit: true };
  }
  const resolved = await nextResolve(specifier, context);
  if (resolved.url.startsWith("file:") && resolved.url.endsWith(".json")) {
    return { ...resolved, importAttributes: { type: "json" } };
  }
  return resolved;
}
