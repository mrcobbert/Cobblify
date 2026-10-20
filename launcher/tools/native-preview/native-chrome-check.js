// Native event/DOM timing only: works under occlusion, never claims painted animation.
(async () => {
  const invoke = window.__TAURI__.core.invoke;
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
  const report = (value) => invoke('preview_report', { message: JSON.stringify(value) });
  const assert = (ok, message) => { if (!ok) throw new Error(message); };
  const events = [];
  const geometry = () => {
    const dash = document.querySelector('#stage').dataset.view === 'dash';
    return {
      at: performance.now(), view: dash ? 'dash' : 'home',
      inset: parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--window-top-inset')),
      mainY: document.querySelector(dash ? '#stage' : '#main')?.getBoundingClientRect().y,
      titleY: document.querySelector(dash ? '#dash-wm .wordmark' : '.hdr .wordmark')?.getBoundingClientRect().y,
      badgeY: document.querySelector(dash ? '#dash-dev' : '.hdr .tag.dev')?.getBoundingClientRect().y,
    };
  };
  document.addEventListener('cobblify:window-chrome', () => {
    const event = geometry();
    events.push(event);
    Promise.all([invoke('window_top_inset'), invoke('preview_fullscreen')]).then(([native, full]) => {
      Object.assign(event, { native, full });
      return report(event);
    }).catch((error) => { event.error = String(error); });
  });
  const until = async (fn, label) => {
    const deadline = performance.now() + 10000;
    while (performance.now() < deadline) {
      const result = await fn();
      if (result) return result;
      await sleep(30);
    }
    throw new Error(`Timed out: ${label}`);
  };
  try {
    await until(() => document.querySelector('.gear') && document.querySelector('.wordmark'), 'frontend ready');
    await window.__COBBLIFY_GUI_TEST__.ready;
    await document.fonts.ready;
    await invoke('preview_show');
    await sleep(500);
    const baseline = geometry();
    assert(baseline.inset > 0, 'Expected initial title-bar inset');
    assert(Math.abs(baseline.mainY - baseline.inset) < 1, 'Main content must reserve the native bar');
    for (let cycle = 1; cycle <= 3; cycle++) {
      await invoke('preview_toggle_fullscreen');
      await until(async () => {
        const state = await invoke('window_top_inset');
        return state.phase === 'stable' && state.inset === 0 && await invoke('preview_fullscreen');
      }, 'fullscreen entry complete');
      const start = events.length;
      await invoke('preview_toggle_fullscreen');
      const early = await until(() => events.slice(start).find((e) => e.native?.phase === 'exiting' && e.full && e.inset > 0), 'DOM restored before native completion');
      assert(Math.abs(early.mainY - baseline.mainY) < 1, 'Early main position differs from windowed baseline');
      assert(Math.abs(early.titleY - baseline.titleY) < 1, 'Early title position differs from windowed baseline');
      assert(Math.abs(early.badgeY - baseline.badgeY) < 1, 'Early Preview badge position differs from windowed baseline');
      await until(async () => (await invoke('window_top_inset')).phase === 'stable' && !await invoke('preview_fullscreen'), 'fullscreen exit complete');
      await sleep(100);
      const final = geometry();
      assert(Math.abs(final.titleY - early.titleY) < 1, 'Title moved after native exit completion');
      assert(Math.abs(final.badgeY - early.badgeY) < 1, 'Preview badge moved after native exit completion');
      await report({ cycle, early, final, scope: 'native events and DOM; visible animation requires manual testing' });
    }
    await invoke('preview_finish', { failed: false });
  } catch (error) {
    await report({ error: String(error), events });
    await invoke('preview_finish', { failed: true });
  }
})();
