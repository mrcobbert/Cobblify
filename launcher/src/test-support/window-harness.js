import { readFileSync } from "node:fs";
import { register } from "node:module";

import { JSDOM, VirtualConsole } from "jsdom";

/**
 * Boots the real launcher window - `index.html` plus the real `main.js` - in
 * JSDOM with a scripted Tauri bridge, so window bugs are pinned against the
 * shipping module instead of a re-implementation of it.
 *
 * `main.js` is module-scoped: it runs its boot the moment it is imported and
 * keeps every control, timer and controller in module state. One process can
 * therefore boot it once, which is why a test file calls `bootWindow` once at
 * the top and then drives the app through the real DOM controls and the
 * scripted `invoke` replies.
 *
 * JSDOM has no WebGL, so the hero scene cannot boot here: the renderer
 * constructor throws (three 0.181 reports it as `TypeError: error is not a
 * function` - its own catch block shadows its logger), `createHeroScene`
 * reports it through `console.error` and answers null, and `main.js` carries
 * on without a scene. That one stack trace per window test file is expected
 * output, not a failure.
 */

const INDEX_HTML = new URL("../../index.html", import.meta.url);
/** The tag Vite injects; the harness imports `main.js` itself instead. */
const MODULE_SCRIPT = /<script type="module"[^>]*><\/script>/;

let hooksRegistered = false;
function registerLoaderHooks() {
  if (hooksRegistered) return;
  register(new URL("./css-stub-loader.mjs", import.meta.url));
  hooksRegistered = true;
}

/** JSDOM has no ResizeObserver; the window only uses it to re-run a layout pass. */
class StubResizeObserver {
  constructor(callback) {
    this.callback = callback;
  }
  observe() {}
  unobserve() {}
  disconnect() {}
}

function defineGlobal(key, value) {
  Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
}

/**
 * `main.js` uses bare `setTimeout`/`setInterval`, so under Node they are
 * Node's. Its poll intervals and the updater's six-hour timer would hold the
 * event loop open forever and `node --test` would never exit, so every timer
 * the app arms is unref'd. The harness keeps the unwrapped originals for its
 * own waiting, which must hold the loop open.
 */
function unrefWrap(fn) {
  return (...args) => {
    const timer = fn(...args);
    if (timer && typeof timer.unref === "function") timer.unref();
    return timer;
  };
}

/**
 * @param {{ replies?: Record<string, unknown>, url?: string }} opts
 *   `replies` maps a Tauri command name to its reply: a value, or a function
 *   (sync or async) called with the command arguments. The object is kept by
 *   reference, so a test rewrites an entry between steps.
 */
export async function bootWindow({ replies = {}, url = "http://localhost/" } = {}) {
  registerLoaderHooks();

  const html = readFileSync(INDEX_HTML, "utf8").replace(MODULE_SCRIPT, "");
  const dom = new JSDOM(html, {
    url,
    pretendToBeVisual: true,
    // Swallows JSDOM's own "not implemented" notices (canvas getContext, CSS
    // parsing) - they are not launcher output.
    virtualConsole: new VirtualConsole(),
  });
  const { window } = dom;

  /** @type {{ command: string, args: unknown }[]} */
  const calls = [];
  const invoke = (command, args) => {
    calls.push({ command, args });
    const reply = replies[command];
    return typeof reply === "function"
      ? Promise.resolve().then(() => reply(args))
      : Promise.resolve(reply);
  };

  // Installed before the import: `main.js` reads `window.__TAURI__.core.invoke`
  // and decides `previewing` at module evaluation time.
  window.__TAURI__ = {
    core: { invoke },
    event: { listen: async () => () => {} },
  };

  const realSetTimeout = globalThis.setTimeout;
  defineGlobal("window", window);
  defineGlobal("document", window.document);
  defineGlobal("location", window.location);
  defineGlobal("HTMLElement", window.HTMLElement);
  defineGlobal("getComputedStyle", (...args) => window.getComputedStyle(...args));
  defineGlobal("ResizeObserver", StubResizeObserver);
  defineGlobal("requestAnimationFrame", (cb) => {
    const timer = realSetTimeout(() => cb(Date.now()), 16);
    timer.unref?.();
    return timer;
  });
  defineGlobal("setTimeout", unrefWrap(globalThis.setTimeout));
  defineGlobal("setInterval", unrefWrap(globalThis.setInterval));

  await import("../main.js");

  const sleep = (ms) => new Promise((done) => realSetTimeout(done, ms));

  /** Let the app's own timers and awaited replies run for `ms`. */
  const tick = (ms = 0) => sleep(ms);

  async function waitFor(predicate, { timeout = 2000, label = "condition" } = {}) {
    const deadline = Date.now() + timeout;
    for (;;) {
      if (predicate()) return;
      if (Date.now() >= deadline) throw new Error(`timed out waiting for ${label}`);
      await sleep(5);
    }
  }

  await tick(0);

  return {
    dom,
    window,
    document: window.document,
    el: (id) => window.document.getElementById(id),
    replies,
    calls,
    countCalls: (command) => calls.filter((c) => c.command === command).length,
    tick,
    waitFor,
  };
}
