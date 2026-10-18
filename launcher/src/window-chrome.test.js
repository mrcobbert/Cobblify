import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { installWindowChrome } from "./window-chrome.js";

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
