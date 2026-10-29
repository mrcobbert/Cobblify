// Only evaluated when the separate fixture app is explicitly started with --check.
(async () => {
  const invoke = window.__TAURI__.core.invoke;
  const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
  const until = async (condition, label) => {
    const deadline = Date.now() + 12000;
    while (!(await condition())) {
      if (Date.now() > deadline) throw Error(`timeout: ${label}`);
      await pause(50);
    }
  };
  const report = data => invoke("preview_report", { message: JSON.stringify(data) });
  window.addEventListener("error", event => report({ phase: "page-error", message: event.message }));
  window.addEventListener("unhandledrejection", event => report({ phase: "page-error", message: String(event.reason) }));
  const rect = selector => {
    const element = document.querySelector(selector);
    if (!element) return null;
    const r = element.getBoundingClientRect();
    return { x: r.x, y: r.y, width: r.width, height: r.height, bottom: r.bottom };
  };
  const snapshot = () => ({
    inset: parseFloat(getComputedStyle(document.documentElement).getPropertyValue("--window-top-inset")) || 0,
    width: innerWidth, height: innerHeight,
    main: rect("#main"), wordmark: rect(".wordmark"), art: rect("#art"), island: rect(".isl3d") ?? rect("#isl"),
    launch: rect(".split"), menu: rect("#client-menu"),
    sheet: rect("#sheet"), sheetOpen: !!document.querySelector("#sheet.open"),
    appHidden: document.querySelector("#app").hidden,
    gearExpanded: document.querySelector(".gear")?.getAttribute("aria-expanded"),
    stageView: document.querySelector("#stage")?.dataset.view,
    modal: !!document.querySelector(".modal"), hidden: document.hidden, focused: document.hasFocus(),
    sheetTransform: getComputedStyle(document.querySelector("#sheet")).transform,
    sheetVisibility: getComputedStyle(document.querySelector("#sheet")).visibility,
    webGL: !!document.querySelector(".isl3d canvas"),
  });
  const equal = (actual, expected, label) => {
    if (!Number.isFinite(actual) || !Number.isFinite(expected) || Math.abs(actual - expected) > 0.5) {
      throw Error(`${label}: ${actual} != ${expected}`);
    }
  };
  const sameViewport = (actual, expected) => {
    equal(actual.width, expected.width, "viewport width changed; rerun without window/display changes");
    equal(actual.height, expected.height, "viewport height changed; rerun without window/display changes");
  };
  try {
    await invoke("preview_show");
    try {
      await until(() => !document.hidden, "visible WKWebView in the active GUI session");
    } catch {
      await invoke("preview_unavailable");
      return;
    }
    await until(() => document.querySelector(".gear") && snapshot().island, "ready home");
    await document.fonts?.ready;
    await pause(3000); // Let the production intro finish before measuring.
    const original = snapshot();
    if (!original.webGL) throw Error("native check requires the real WebGL island; fallback alone is insufficient");
    await report({ phase: "before", ...original });
    for (let cycle = 1; cycle <= 3; cycle++) {
      document.querySelector(".caret").click();
      await invoke("preview_toggle_fullscreen");
      await until(() => invoke("preview_fullscreen"), "enter fullscreen");
      await pause(1400);
      equal(snapshot().inset, 0, "fullscreen inset");
      await invoke("preview_toggle_fullscreen");
      await until(async () => !(await invoke("preview_fullscreen")), "exit fullscreen");
      await pause(1800);
      const exited = snapshot();
      sameViewport(exited, original);
      equal(exited.inset, original.inset, "restored inset");
      equal(exited.main.y, original.main.y, "restored main position");
      equal(exited.wordmark.y, original.wordmark.y, "restored wordmark position");
      equal(exited.island.y, original.island.y, "island returns to original position");
      if (exited.menu) equal(exited.menu.y - exited.launch.bottom, 4, "client menu stays anchored");
      document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
      // Exercise the real native blur/focus bridge, then open Settings while the
      // production 400ms focus refresh is still queued (the original jump trigger).
      await invoke("preview_focus_cycle");
      await pause(430);
      if (document.hidden) {
        await invoke("preview_unavailable");
        return;
      }
      const gear = document.querySelector(".gear");
      gear.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true, pointerId: 1, pointerType: "mouse", button: 0 }));
      gear.click();
      const immediate = snapshot();
      sameViewport(immediate, exited);
      await report({ phase: "immediate", cycle, ...immediate });
      if (!immediate.sheetOpen) throw Error("Settings click did not open the sheet");
      equal(immediate.island.y, exited.island.y, "immediate Settings island position");
      await pause(900);
      const opened = snapshot();
      sameViewport(opened, exited);
      await report({ phase: "opened", cycle, ...opened });
      equal(opened.island.y, exited.island.y, "opening Settings preserves island position");
      equal(opened.sheet.y, 0, "sheet reaches top");
      equal(opened.sheet.bottom, opened.height, "sheet reaches bottom");
      equal(opened.sheet.x + opened.sheet.width, opened.width, "open sheet is visible at right edge");
      await report({ phase: "cycle", cycle, exited, immediate, opened });
      document.querySelector("#sheet [data-act=close]").click();
      await pause(250);
    }
    await invoke("preview_finish", { failed: false });
  } catch (error) {
    await report({ phase: "error", message: String(error) });
    await invoke("preview_finish", { failed: true });
  }
})();
