// Injected only into the isolated test app. Production launch/session/render code runs unchanged.
window.__COBBLIFY_GUI_TEST__ = { ready: (async () => {
  const invoke = window.__TAURI__.core.invoke;
  const until = async (fn) => {
    const deadline = Date.now() + 12000;
    while (!fn()) {
      if (Date.now() > deadline) throw Error('GUI fixture did not become ready');
      await new Promise((resolve) => setTimeout(resolve, 30));
    }
  };
  await until(() => document.querySelector('.gear') && document.querySelector('#dash-wm .wordmark'));
  const scene = await invoke('preview_scene');
  const label = document.createElement('label');
  label.style.cssText = 'position:fixed;bottom:4px;right:6px;z-index:99999;font:11px system-ui;color:#c8d5d2;background:#1a1a1aee;padding:2px 4px';
  label.textContent = 'Test view: ';
  const select = document.createElement('select');
  select.setAttribute('aria-label', 'GUI test view');
  select.style.cssText = 'font:inherit;color:inherit;background:#303936;border:1px solid #657873';
  for (const [value, text] of [['home','Home'],['join','Join Hypixel'],['lobby','Lobby'],['queueFours','Full queue'],['gameSolo','Solo game'],['gameDoubles','Doubles game'],['unsupported','Not Available']]) {
    select.add(new Option(text, value, false, value === scene));
  }
  select.addEventListener('change', async () => {
    select.disabled = true;
    try {
      await invoke('preview_scene', { scene: select.value });
      location.reload();
    } catch (error) {
      select.disabled = false;
      await invoke('preview_report', { message: String(error) });
    }
  });
  label.append(select);
  document.body.append(label);
  if (scene !== 'home') {
    // Explicit fixture scene arms a simulated launch. No game or installation code exists here.
    await until(() => document.querySelector('[data-act="play"]:not(:disabled)'));
    await document.fonts.ready;
    document.querySelector('[data-act="play"]').click();
    await until(() => document.querySelector('#stage').dataset.view === 'dash'
      && (scene === 'join' ? document.querySelector('#joining.on') : document.querySelector('#dash.on [class]')));
  }
  return scene;
})() };
window.__COBBLIFY_GUI_TEST__.ready.catch((error) => window.__TAURI__.core.invoke('preview_report', { message: String(error) }));
