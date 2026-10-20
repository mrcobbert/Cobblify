/** Real-browser layout/GPU checks against `npm run preview` (fixture mode).
 * Requires Playwright and its Chromium browser; see tools/native-preview/README.md.
 * No game, user configuration, or updater commands are used.
 */
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { pathToFileURL } from "node:url";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : "playwright");
const base = process.argv[2] || "http://127.0.0.1:1440/";
const url = (font) => `${base.replace(/\/$/, "")}/?state=bothReady&update=currentDev&font=${font}`;
const browser = await chromium.launch({
  headless: true,
  ...(process.env.PLAYWRIGHT_CHROMIUM ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM } : {}),
});
const errors = [];
const near = (actual, expected, label) => assert.ok(Math.abs(actual - expected) <= 1, `${label}: ${actual} != ${expected}`);
const noFocusFrame = async (locator, split = false) => {
  const style = await locator.evaluate(el => ({
    focused: el === document.activeElement,
    visible: el.matches(":focus-visible"),
    outline: getComputedStyle(el).outlineWidth,
    after: getComputedStyle(el, "::after").content,
  }));
  assert.equal(style.focused, true, "keyboard focus is preserved");
  assert.equal(style.visible, true, "exercise the keyboard focus styling");
  assert.equal(style.outline, "0px", "no separate focus rectangle");
  if (split) assert.equal(style.after, "none", "no split-button focus frame");
};
const boxes = (page) => page.evaluate(() => {
  const box = (s) => {
    const r = document.querySelector(s)?.getBoundingClientRect();
    return r ? { top: r.top, bottom: r.bottom, left: r.left, right: r.right, width: r.width, height: r.height } : null;
  };
  return { island: box(".isl3d"), art: box("#art"), launch: box(".split"), menu: box("#client-menu"), sheet: box("#sheet"), su: parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--su")) };
});
const changeInset = async (page, inset) => {
  await page.evaluate((value) => {
    document.documentElement.style.setProperty("--window-top-inset", `${value}px`);
    // Same notification sent by window-chrome after an accepted native reply.
    document.dispatchEvent(new CustomEvent("cobblify:window-chrome"));
  }, inset);
  await page.waitForTimeout(100);
};
try {
  for (const font of ["minecraft", "modern"]) {
    for (const viewport of [{ width: 640, height: 480 }, { width: 900, height: 552 }, { width: 1440, height: 900 }]) {
      const page = await browser.newPage({ viewport, reducedMotion: "reduce" });
      page.on("pageerror", (e) => errors.push(e.message));
      await page.goto(url(font));
      await page.locator(".isl3d canvas").waitFor();
      await page.waitForTimeout(700);
      const initial = await boxes(page);
      await page.locator(".caret").click();
      for (const inset of [32, 0, 32, 0, 32]) {
        await changeInset(page, inset);
        const b = await boxes(page);
        near(b.island.top, initial.island.top, "inset cannot move island");
        near(b.art.top, 0, "art stays aligned with window");
        // A tall menu may open upward at minimum size.
        const gap = Math.min(Math.abs(b.menu.top - b.launch.bottom), Math.abs(b.launch.top - b.menu.bottom));
        near(gap, 2 * b.su, "menu follows Launch");
      }
      await page.keyboard.press("Escape");
      await page.locator(".gear").click();
      await page.waitForTimeout(250);
      near((await boxes(page)).sheet.top, 0, "sheet reaches top of window");
      // A normal focus/theme redraw must not correct a previously stale position.
      await page.evaluate(() => document.dispatchEvent(new CustomEvent("cobblify:theme")));
      await page.waitForTimeout(500);
      near((await boxes(page)).island.top, initial.island.top, "opening Settings/redrawing cannot jump island");
      await page.close();
    }
  }

  const keyboard = await browser.newPage({ viewport: { width: 900, height: 552 }, reducedMotion: "reduce" });
  keyboard.on("pageerror", (e) => errors.push(e.message));
  await keyboard.goto(`${url("minecraft")}&open=updated&version=0.18.1`);
  await keyboard.locator(".modal").waitFor();
  for (const key of ["Tab", "Tab", "Tab", "Shift+Tab", "Shift+Tab", "Shift+Tab"]) {
    await keyboard.keyboard.press(key);
    assert.equal(await keyboard.evaluate(() => document.querySelector(".modal").contains(document.activeElement)), true, "real Tab navigation remains inside What's New");
    await noFocusFrame(keyboard.locator(".modal :focus"));
  }
  await keyboard.keyboard.press("Escape");
  await noFocusFrame(keyboard.locator("#main .hero"), true);
  await keyboard.locator(".caret").focus();
  await noFocusFrame(keyboard.locator(".caret"), true);
  await keyboard.keyboard.press("ArrowDown");
  await noFocusFrame(keyboard.locator("#client-menu :focus"));
  await keyboard.keyboard.press("Escape");
  await keyboard.locator(".gear").focus();
  await noFocusFrame(keyboard.locator(".gear"));
  await keyboard.locator(".gear").click();
  await keyboard.locator('#sheet [data-key="auto"]').focus();
  await keyboard.waitForTimeout(180);
  assert.equal(await keyboard.evaluate(() => document.activeElement.dataset.key), "auto", "opening timer respects the focused switch");
  await keyboard.keyboard.press("Tab");
  await noFocusFrame(keyboard.locator("#sheet :focus"));
  await keyboard.locator("#sheet .version-link").focus();
  await noFocusFrame(keyboard.locator("#sheet .version-link"));
  await keyboard.locator('#sheet [data-act="history"]').click();
  await keyboard.locator(".hist-list .row").last().click();
  const position = await keyboard.locator("#reading").evaluate((el) => { el.scrollTop = 500; return el.scrollTop; });
  assert.ok(position > 0, "history is scrolled before refresh");
  await keyboard.evaluate(() => document.dispatchEvent(new CustomEvent("cobblify:theme")));
  assert.equal(await keyboard.locator("#reading").evaluate((el) => el.scrollTop), position, "history retains scroll through a full redraw");
  await keyboard.close();

  const saving = await browser.newPage({ viewport: { width: 900, height: 552 }, reducedMotion: "reduce" });
  saving.on("pageerror", (e) => errors.push(e.message));
  // Exercise a real disabled-control focus transition while channel persistence
  // is pending. The ordinary browser fixture saves immediately and misses it.
  await saving.addInitScript(() => {
    const prefs = { autoUpdateEnabled: true, autoUpdatePrompted: true, updateChannel: "stable", health: "valid" };
    const ready = { state: "ready", mod_version: "0.18.1", targets: ["lunar", "forge"].map(kind => ({ kind, state: "ready", message: kind, action: "none", candidates: [] })) };
    window.__TAURI__ = {
      core: { invoke: async (command, args) => {
        if (command === "set_update_channel") {
          await new Promise(resolve => setTimeout(resolve, 500));
          return { ...prefs, status: "saved", updateChannel: args.channel };
        }
        if (command === "status" || command === "refresh_setup") return ready;
        if (command === "open_info") return { version: "0.18.1", updated: false, fresh: false };
        if (command === "game_theme") return { font: "minecraft", accent: "pale-pink" };
        if (command === "launch_preferences") return { useExternalOverlay: true, health: "valid" };
        if (command === "update_preferences") return prefs;
        if (command === "update_status" || command === "check_for_update") return { ...prefs, state: "current", currentVersion: "0.18.1" };
        if (command === "lobby_state") return { kind: "unavailable" };
        if (command === "window_top_inset") return 0;
        if (command === "lunar_version_ready") return "ready";
        return null;
      } },
      event: { listen: async () => () => {} },
      window: { getCurrentWindow: () => ({ show: async () => {}, onResized: async () => () => {} }) },
    };
  });
  await saving.goto(base);
  await saving.locator(".gear").waitFor();
  await saving.waitForTimeout(700);
  await saving.locator(".gear").click();
  await saving.locator('#sheet [data-key="dev"]').click();
  assert.equal(await saving.locator('#sheet [data-key="dev"]').isDisabled(), true, "save is pending");
  await saving.waitForTimeout(180);
  assert.notEqual(await saving.evaluate(() => document.activeElement.dataset.act), "close", "opening timer cannot steal focus during a pending save");
  await saving.waitForTimeout(550);
  await saving.close();

  const page = await browser.newPage({ viewport: { width: 900, height: 552 }, reducedMotion: "reduce" });
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(url("minecraft"));
  const canvas = page.locator(".isl3d canvas");
  await canvas.waitFor();
  await page.waitForTimeout(1200);
  const initialPng = await canvas.screenshot();
  const colors = await page.evaluate(async (encoded) => {
    const image = new Image(); image.src = `data:image/png;base64,${encoded}`; await image.decode();
    const offscreen = document.createElement("canvas"); offscreen.width = image.width; offscreen.height = image.height;
    const ctx = offscreen.getContext("2d"); ctx.drawImage(image, 0, 0);
    const pixels = ctx.getImageData(0, 0, image.width, image.height).data, found = new Set();
    for (let i = 0; i < pixels.length; i += 4) found.add(`${pixels[i]},${pixels[i + 1]},${pixels[i + 2]}`);
    return found.size;
  }, initialPng.toString("base64"));
  assert.ok(colors > 10, "baseline island is visible");
  const digest = (data) => createHash("sha256").update(data).digest("hex");
  for (let cycle = 0; cycle < 3; cycle++) {
    await canvas.evaluate(async (c) => {
      const gl = c.getContext("webgl2"), ext = gl.getExtension("WEBGL_lose_context");
      if (!ext) throw new Error("WEBGL_lose_context is required");
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error("Context did not restore")), 5000);
        c.addEventListener("webglcontextrestored", () => { clearTimeout(timer); resolve(); }, { once: true });
        c.addEventListener("webglcontextlost", () => setTimeout(() => ext.restoreContext(), 100), { once: true });
        ext.loseContext();
      });
      if (gl.isContextLost()) throw new Error("Restored context remains lost");
    });
    await page.waitForTimeout(500);
    assert.equal(digest(await canvas.screenshot()), digest(initialPng), "idle GPU restoration redraws identical pixels without input");
  }
  await page.close();

  // Exercise the actual fallback branch, rather than mocking the island layout.
  const fallbackBrowser = await chromium.launch({ headless: true, args: ["--disable-webgl"], ...(process.env.PLAYWRIGHT_CHROMIUM ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM } : {}) });
  try {
    const fallback = await fallbackBrowser.newPage({ viewport: { width: 900, height: 552 }, reducedMotion: "reduce" });
    fallback.on("pageerror", (e) => errors.push(e.message));
    await fallback.goto(url("minecraft"));
    await fallback.locator(".gear").waitFor();
    await fallback.waitForTimeout(500);
    for (const inset of [0, 32, 0]) {
      await changeInset(fallback, inset);
      const b = await fallback.evaluate(() => {
        const image = document.querySelector("#isl").getBoundingClientRect(), gear = document.querySelector(".gear").getBoundingClientRect();
        return { left: image.left, right: image.right, center: (image.left + image.right) / 2, controlsRight: gear.right };
      });
      assert.ok(b.left > b.controlsRight, "fallback clears the controls");
      near(b.center, 675, "fallback uses right-half slot");
    }
  } finally { await fallbackBrowser.close(); }
  assert.deepEqual(errors, []);
  console.log("PASS: 6 font/size layouts, repeated inset/menu/sheet transitions, keyboard/focus/history, 3 idle GPU recoveries, no-WebGL fallback");
} finally { await browser.close(); }
