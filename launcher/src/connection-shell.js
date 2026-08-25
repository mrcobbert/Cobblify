import { connectionCopy } from "./connection-view.js";

/**
 * Dependency-injected connection shell controller (no DOM/CSS imports).
 *
 * @param {{
 *   titleEl: { textContent: string },
 *   subEl: { textContent: string },
 *   shellEl: { classList: { toggle: Function }, dataset: Record<string, string>, setAttribute: Function },
 *   actionEl?: { hidden: boolean, textContent: string, addEventListener: Function },
 *   actions?: Record<string, { label: string, onAction: () => void }>,
 *   announceEl?: { textContent: string },
 *   clearRoster: () => void,
 *   stopProgress: () => void,
 *   showDash: () => void,
 *   hideDash: () => void,
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

  // One action slot, configured per mode: `preexisting_game` retries the
  // launch, `waiting` escapes back home. A mode with no entry has no button.
  const actions = deps.actions ?? {};
  const actionFor = (m) => actions[m] ?? null;

  if (deps.actionEl) {
    deps.actionEl.addEventListener("click", () => actionFor(mode)?.onAction());
  }

  function applyCopy() {
    const copy = connectionCopy(mode);
    deps.titleEl.textContent = copy.title;
    deps.subEl.textContent = subtitleOverride ?? copy.subtitle;
    deps.shellEl.dataset.conn = mode;
    deps.shellEl.setAttribute("aria-busy", copy.loading ? "true" : "false");
    if (deps.actionEl) {
      const action = actionFor(mode);
      if (action) deps.actionEl.textContent = action.label;
      deps.actionEl.hidden = !action;
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
    if (deps.actionEl) deps.actionEl.hidden = true;
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
    if (deps.actionEl) {
      // A terminal mode only offers its action when the caller asks for it;
      // the reset-to-home terminals deliberately have no escape button.
      deps.actionEl.hidden = !showTryAgain || !actionFor(modeNext);
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
    if (deps.actionEl) deps.actionEl.hidden = true;
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
