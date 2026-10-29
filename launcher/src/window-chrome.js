/** Only main content reserves the native Mac bar; overlays fill the webview. */
export async function installWindowChrome(win, invoke) {
  let generation = 0, nativeRevision = -1;
  const apply = (inset) => {
    const style = win.document.documentElement.style, value = `${inset}px`;
    if (style.getPropertyValue("--window-top-inset") === value) return;
    style.setProperty("--window-top-inset", value);
    win.document.dispatchEvent(new win.CustomEvent("cobblify:window-chrome"));
  };
  const accept = (value, request) => {
    if (value && typeof value === "object") {
      if (!Number.isSafeInteger(value.revision) || value.revision < 0 || value.revision <= nativeRevision
        || !Number.isFinite(value.inset) || value.inset < 0) return;
      nativeRevision = value.revision;
      apply(value.inset);
    } else if (nativeRevision < 0 && request === generation && Number.isFinite(value) && value >= 0) {
      // Legacy fixtures/native shells return a scalar and have no early events.
      apply(value);
    }
  };
  const update = async () => {
    const request = ++generation;
    try { accept(await invoke("window_top_inset"), request); } catch { /* retain known geometry */ }
  };
  win.addEventListener("resize", update);
  const native = win.__TAURI__?.window?.getCurrentWindow?.();
  try {
    // Subscribe before reading state, so a transition during setup is not lost.
    await native?.listen?.("window-chrome://changed", ({ payload }) => accept(payload));
  } catch { /* resize/query fallback */ }
  try { await native?.onResized?.(update); } catch { /* browser resize fallback */ }
  await update();
}
