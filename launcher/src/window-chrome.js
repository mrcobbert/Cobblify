/** Only the main content reserves the native Mac bar; overlays fill the entire webview. */
export async function installWindowChrome(win, invoke) {
  let generation = 0;
  const update = async () => {
    const request = ++generation;
    try {
      const inset = await invoke("window_top_inset");
      if (request === generation && Number.isFinite(inset) && inset >= 0) {
        win.document.documentElement.style.setProperty("--window-top-inset", `${inset}px`);
      }
    } catch {
      // A browser preview has no native frame. Native failures retain the last known inset.
    }
  };
  win.addEventListener("resize", update);
  await update();
}
