/** Production browser fixture: dashboard title parity, usable height and channel labels. */
import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE
  ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : "playwright");
const base = (process.argv[2] || "http://127.0.0.1:1440/").replace(/\/$/, "");
const browser = await chromium.launch({ headless: true,
  ...(process.env.PLAYWRIGHT_CHROMIUM ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM } : {}) });
const failures = [], errors = [];
let checks = 0;
const check = (ok, message) => { checks++; if (!ok) failures.push(message); };
const sample = (page, home = false) => page.evaluate((home) => {
  const rect = (selector) => {
    const r = document.querySelector(selector)?.getBoundingClientRect();
    return r ? { y: r.y, x: r.x, height: r.height, bottom: r.bottom } : null;
  };
  const header = home ? ".hdr" : ".dhdr";
  return { mark: rect(`${header} .wordmark`), badge: rect(`${header} .tag.dev`),
    stage: rect("#stage"), board: rect("#dash"), msg: rect("#joining.on .msg"),
    mode: document.querySelector("#dash").dataset.mode };
}, home);
const inset = async (page, value) => {
  await page.evaluate((value) => {
    document.documentElement.style.setProperty("--window-top-inset", `${value}px`);
    document.dispatchEvent(new CustomEvent("cobblify:window-chrome"));
  }, value);
  await page.evaluate(async () => { await document.fonts.ready; delete window.__dashboardGeometry; });
  // Wait for stable actual geometry, rather than counting frames whose callbacks
  // can precede the production ResizeObserver and its coalesced layout frame.
  await page.waitForFunction(() => {
    const boxes = ['#stage', '#dash', '#joining .msg', '#dash .msg'].map((selector) => {
      const el = document.querySelector(selector), r = el?.getBoundingClientRect();
      return r ? [r.y, r.height, el.style.top] : null;
    });
    const key = JSON.stringify(boxes), previous = window.__dashboardGeometry;
    const count = previous?.key === key ? previous.count + 1 : 0;
    window.__dashboardGeometry = { key, count };
    return count >= 3;
  }, null, { polling: 'raf', timeout: 5000 });
};
try {
  for (const dpr of [1, 2]) for (const font of ["minecraft", "modern"]) {
    for (const viewport of [{ width: 640, height: 480 }, { width: 900, height: 552 }]) {
      const context = await browser.newContext({ viewport, deviceScaleFactor: dpr, reducedMotion: "reduce" });
      const page = await context.newPage();
      page.on("pageerror", (e) => errors.push(e.message));
      const url = `${base}/?state=bothReady&update=currentDev&font=${font}`;
      await page.goto(url);
      await page.waitForFunction((font) => document.documentElement.dataset.font === font, font);
      await page.locator(".hdr .wordmark").waitFor();
      await page.evaluate(() => document.fonts.ready);
      await inset(page, 32);
      const home = await sample(page, true);
      assert.equal(await page.locator('.hdr .tag.dev').textContent(), "Preview");
      await page.locator('.gear').click();
      const channel = page.getByRole('switch', { name: 'Preview updates', exact: true });
      await channel.click();
      await page.waitForFunction(() => document.querySelector('#dash-dev').hidden && !document.querySelector('.hdr .tag.dev'));
      check((await sample(page, true)).mark.y === home.mark.y, 'Channel badge removal cannot move home title');
      await channel.click();
      await page.waitForFunction(() => !document.querySelector('#dash-dev').hidden && document.querySelector('.hdr .tag.dev'));
      check((await sample(page, true)).mark.y === home.mark.y, 'Channel badge insertion cannot move home title');
      for (const ctx of ["disconnected", "lobby", "queueFours", "gameSolo", "gameDoubles", "unsupported"]) {
        await page.goto(`${url}&ctx=${ctx}`);
        await page.locator(".dhdr .wordmark").waitFor();
        await page.waitForFunction((font) => document.documentElement.dataset.font === font, font);
        await page.evaluate(() => document.fonts.ready);
        for (const n of [32, 0, 28, 0, 32]) {
          await inset(page, n);
          const s = await sample(page), label = `${font}/${dpr}x/${viewport.width}/${ctx}/${n}`;
          check(Math.abs(s.mark.y - (home.mark.y + n - 32)) < 0.1, `${label}: title differs from home`);
          check(Math.abs(s.badge.y - (home.badge.y + n - 32)) < 0.1, `${label}: badge differs from home`);
          check(s.mark.x === home.mark.x, `${label}: title moves horizontally`);
          if (s.mode && !s.msg) check(s.board.bottom <= s.stage.bottom + 0.5, `${label}: board clipped below stage (${s.board.bottom} > ${s.stage.bottom})`);
          if (s.mode === "roster") {
            const end = await page.locator('#dash').evaluate((el) => {
              el.scrollTop = el.scrollHeight;
              return { panelBottom: el.querySelector('.panel').getBoundingClientRect().bottom,
                bottom: el.getBoundingClientRect().bottom };
            });
            check(end.panelBottom <= end.bottom + 0.5, `${label}: final roster rows are unreachable`);
          }
          const msg = s.msg || (s.mode === "note" ? await page.locator("#dash .msg").boundingBox() : null);
          if (msg) check(Math.abs(msg.y + msg.height / 2 - (s.stage.y + s.stage.height / 2)) <= 0.5,
            `${label}: message not centered in available stage (${msg.y + msg.height / 2} vs ${s.stage.y + s.stage.height / 2})`);
        }
      }
      await context.close();
    }
  }
  assert.deepEqual(errors, [], "no browser errors");
  assert.equal(failures.length, 0, `${failures.length} dashboard geometry failures:\n${failures.slice(0, 15).join("\n")}`);
  console.log(`PASS: ${checks} dashboard geometry checks across 48 font/scale/size/view configurations`);
} finally { await browser.close(); }
