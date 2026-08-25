/**
 * Session-ended-to-home orchestrator with an exact commit point.
 *
 * Sequence: invalidate every in-flight async result FIRST (stop polling,
 * bump generations), then attempt the backend reset. Both success variants
 * commit the home UI atomically; `busy`, `not_terminal`, and transport
 * errors show truthful transitional copy and retry on a bounded timer,
 * settling on the honest final quit-and-reopen copy when the bound is
 * exhausted.
 *
 * A reset that has not resolved within `preShowMs` shows the same
 * transitional copy up front, so the happy path is never silent for the
 * whole round trip. The pre-show timer is generation-scoped: it is cancelled
 * on success, on final failure, and by any superseding reset, and a timer
 * armed by reset A can never fire into reset B.
 *
 * @param {{
 *   invokeReset: (reason: string) => Promise<{ status: string, preferences?: object }>,
 *   stopPolling: () => void,
 *   invalidate: () => void,
 *   commitHome: (preferences: object | null) => void,
 *   refreshHome?: () => Promise<void>,
 *   onRefreshError?: (e: unknown) => void,
 *   showTransitional: (reason: string) => void,
 *   showFinal: (reason: string) => void,
 *   schedule?: (fn: () => void, ms: number) => number,
 *   cancel?: (id: number) => void,
 *   retryMs?: number,
 *   preShowMs?: number,
 *   maxAttempts?: number,
 * }} deps
 */
export function createSessionReset(deps) {
  const schedule = deps.schedule ?? ((fn, ms) => setTimeout(fn, ms));
  const cancel = deps.cancel ?? clearTimeout;
  const retryMs = deps.retryMs ?? 2000;
  const preShowMs = deps.preShowMs ?? 150;
  const maxAttempts = deps.maxAttempts ?? 15;

  let timerId = null;
  let preShowId = null;
  let active = false;
  let attempts = 0;
  let generation = 0;

  function cancelPreShow() {
    if (preShowId != null) {
      cancel(preShowId);
      preShowId = null;
    }
  }

  async function resetToHome(reason) {
    if (active) return;
    // A superseding reset owns the surface from here on; anything armed by
    // an earlier generation is dead even if its host timer still fires.
    const gen = ++generation;
    active = true;
    attempts = 0;
    deps.stopPolling();
    deps.invalidate();
    preShowId = schedule(() => {
      preShowId = null;
      if (gen !== generation) return;
      deps.showTransitional(reason);
    }, preShowMs);
    await attempt(reason);
  }

  async function attempt(reason) {
    attempts += 1;
    let reply = null;
    try {
      reply = await deps.invokeReset(reason);
    } catch {
      reply = null;
    }
    if (!active) return;
    if (reply && (reply.status === "ok" || reply.status === "already_reset")) {
      active = false;
      cancelPreShow();
      // The wire contract guarantees a preferences view on both success
      // variants; null is a defensive fallback only.
      deps.commitHome(reply.preferences ?? null);
      // Home is already committed; a refresh failure surfaces through the
      // existing setup-error rendering, never a silent stale home.
      if (deps.refreshHome) {
        try {
          await deps.refreshHome();
        } catch (e) {
          deps.onRefreshError?.(e);
        }
      }
      return;
    }
    // busy, not_terminal, or transport error: transitional copy + retry.
    if (attempts >= maxAttempts) {
      active = false;
      cancelPreShow();
      deps.showFinal(reason);
      return;
    }
    cancelPreShow();
    deps.showTransitional(reason);
    timerId = schedule(() => {
      timerId = null;
      attempt(reason);
    }, retryMs);
  }

  /** Cancel any pending retry (on success elsewhere or a view change). */
  function cancelRetry() {
    if (timerId != null) {
      cancel(timerId);
      timerId = null;
    }
    cancelPreShow();
    active = false;
  }

  return { resetToHome, cancelRetry, isActive: () => active };
}
