import { connectionCopy } from "./connection-view.js";

/**
 * Dependency-injected connection shell controller (no DOM/CSS imports).
 *
 * @param {{
 *   titleEl: { textContent: string },
 *   subEl: { textContent: string },
 *   shellEl: { classList: { toggle: Function }, dataset: Record<string, string>, setAttribute: Function },
 *   tryAgainEl?: { hidden: boolean, addEventListener: Function },
 *   announceEl?: { textContent: string },
 *   clearRoster: () => void,
 *   stopProgress: () => void,
 *   showDash: () => void,
 *   hideDash: () => void,
 *   onTryAgain?: () => void,
 *   clock?: () => number,
 *   schedule?: (fn: () => void, ms: number) => number,
 *   cancel?: (id: number) => void,
 * }} deps
 */
export function createConnectionShell(deps) {
  const clock = deps.clock ?? (() => Date.now());
  const schedule = deps.schedule ?? ((fn, ms) => setTimeout(fn, ms));
  const cancel = deps.cancel ?? clearTimeout;

  let visible = false;
  let mode = "joining";
  let announcedConnected = false;
  /** @type {number | null} */
  let timerId = null;
  let subtitleOverride = null;

  if (deps.tryAgainEl && deps.onTryAgain) {
    deps.tryAgainEl.addEventListener("click", () => deps.onTryAgain?.());
  }

  function applyCopy() {
    const copy = connectionCopy(mode);
    deps.titleEl.textContent = copy.title;
    deps.subEl.textContent = subtitleOverride ?? copy.subtitle;
    deps.shellEl.dataset.conn = mode;
    deps.shellEl.setAttribute("aria-busy", copy.loading ? "true" : "false");
    if (deps.tryAgainEl) {
      deps.tryAgainEl.hidden = mode !== "preexisting_game";
    }
  }

  function setVisible(on) {
    visible = on;
    deps.shellEl.classList.toggle("on", on);
    deps.shellEl.setAttribute("aria-hidden", on ? "false" : "true");
  }

  function show(modeNext, { announceConnected = false, subtitle = null } = {}) {
    mode = modeNext;
    subtitleOverride = subtitle;
    applyCopy();
    if (announceConnected && deps.announceEl) {
      deps.announceEl.textContent = announcedConnected ? "" : "Connected to Hypixel";
      if (!announcedConnected) announcedConnected = true;
    }
    setVisible(true);
  }

  function hide() {
    setVisible(false);
    subtitleOverride = null;
    if (deps.tryAgainEl) deps.tryAgainEl.hidden = true;
  }

  function enterConnected({ firstLive = false, reconnect = false } = {}) {
    if (firstLive) {
      if (deps.announceEl) deps.announceEl.textContent = "Connected to Hypixel";
      announcedConnected = true;
    }
    if (reconnect && deps.announceEl) {
      deps.announceEl.textContent = "Reconnected to Hypixel";
    }
    deps.stopProgress();
    hide();
    deps.showDash();
  }

  function enterDisconnected() {
    deps.clearRoster();
    deps.hideDash();
    show("disconnected");
  }

  function enterTerminal(modeNext, { showTryAgain = false } = {}) {
    deps.clearRoster();
    deps.hideDash();
    deps.stopProgress();
    if (timerId != null) {
      cancel(timerId);
      timerId = null;
    }
    show(modeNext);
    if (deps.tryAgainEl) {
      deps.tryAgainEl.hidden = !showTryAgain || modeNext !== "preexisting_game";
    }
  }

  function reset() {
    mode = "joining";
    visible = false;
    announcedConnected = false;
    subtitleOverride = null;
    if (timerId != null) {
      cancel(timerId);
      timerId = null;
    }
    setVisible(false);
    if (deps.tryAgainEl) deps.tryAgainEl.hidden = true;
  }

  function scheduleWaitingDeadline(ms, onWaiting) {
    if (timerId != null) cancel(timerId);
    timerId = schedule(() => {
      timerId = null;
      onWaiting();
    }, ms);
  }

  return {
    reset,
    show,
    hide,
    enterConnected,
    enterDisconnected,
    enterTerminal,
    scheduleWaitingDeadline,
    getVisible: () => visible,
    getMode: () => mode,
  };
}
