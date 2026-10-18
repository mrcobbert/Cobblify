import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { installWindowChrome } from "./window-chrome.js";

test("fullscreen exit restores the inset on native resize without a browser resize", async () => {
  const { window } = new JSDOM("");
  let inset = 32;
  let nativeResize = () => {};
  window.__TAURI__ = {
    window: {
      getCurrentWindow: () => ({
        onResized: async (handler) => { nativeResize = handler; return () => {}; },
      }),
    },
  };
  await installWindowChrome(window, () => Promise.resolve(inset));
  const value = () => window.document.documentElement.style.getPropertyValue("--window-top-inset");
  assert.equal(value(), "32px");

  inset = 0;
  window.dispatchEvent(new window.Event("resize"));
  await Promise.resolve();
  assert.equal(value(), "0px", "fullscreen removes the reserved native bar");

  // macOS resizes before its fullscreen state clears, then emits a native resize
  // at completion with unchanged bounds. WebKit sends no second browser resize.
  nativeResize({ payload: { width: 900, height: 552 } });
  await Promise.resolve();
  assert.equal(value(), "0px");
  inset = 32;
  nativeResize({ payload: { width: 900, height: 552 } });
  await Promise.resolve();
  assert.equal(value(), "32px", "native completion restores spacing without a DOM resize");
  window.close();
});

test("native inset follows fullscreen and ignores stale resize replies", async () => {
  const { window } = new JSDOM("");
  let inset = 28;
  let reply = () => Promise.resolve(inset);
  await installWindowChrome(window, () => reply());
  const value = () => window.document.documentElement.style.getPropertyValue("--window-top-inset");
  assert.equal(value(), "28px");
  let stale;
  reply = () => new Promise((resolve) => { stale = resolve; });
  window.dispatchEvent(new window.Event("resize"));
  inset = 0;
  reply = () => Promise.resolve(inset);
  window.dispatchEvent(new window.Event("resize"));
  await Promise.resolve();
  assert.equal(value(), "0px", "fullscreen has no reserved strip");
  stale(28);
  await Promise.resolve();
  assert.equal(value(), "0px", "an earlier resize cannot restore the strip");
  inset = 28;
  window.dispatchEvent(new window.Event("resize"));
  await Promise.resolve();
  assert.equal(value(), "28px", "leaving fullscreen restores native space");
  window.close();
});
