import test from "node:test";
import assert from "node:assert/strict";

import { createSessionReset } from "./session-reset.js";

function harness({
  replies,
  maxAttempts = 15,
  refreshFails = false,
  withRefresh = false,
  leakTimers = false,
} = {}) {
  const events = [];
  /** @type {Map<number, { fn: Function, ms: number }>} */
  const timers = new Map();
  let nextTimer = 0;
  let call = 0;
  const reasons = [];
  /** @type {null | ((reply: unknown) => void)} */
  let releasePending = null;
  const reset = createSessionReset({
    invokeReset: async (reason) => {
      const next = replies[Math.min(call, replies.length - 1)];
      call += 1;
      reasons.push(reason);
      events.push(`invoke#${call}`);
      if (next === "throw") throw new Error("transport");
      if (next === "pending") {
        return new Promise((resolve) => {
          releasePending = resolve;
        });
      }
      return next;
    },
    stopPolling: () => events.push("stop_polling"),
    invalidate: () => events.push("invalidate"),
    commitHome: (prefs) => events.push(`commit(${prefs ? "prefs" : "null"})`),
    ...(withRefresh
      ? {
          refreshHome: async () => {
            events.push("refresh");
            if (refreshFails) throw new Error("setup refresh failed");
          },
          onRefreshError: (e) => events.push(`refresh_error(${String(e).includes("setup") ? "setup" : "?"})`),
        }
      : {}),
    showTransitional: (reason) => events.push(`transitional(${reason})`),
    showFinal: (reason) => events.push(`final(${reason})`),
    schedule: (fn, ms) => {
      nextTimer += 1;
      timers.set(nextTimer, { fn, ms });
      return nextTimer;
    },
    // A cancelled timer is really gone, exactly like clearTimeout - unless
    // the test deliberately leaks it to prove the generation guard alone.
    cancel: (id) => {
      events.push("cancel_timer");
      if (!leakTimers) timers.delete(id);
    },
    retryMs: 1,
    preShowMs: 150,
    maxAttempts,
  });
  /** Run the earliest still-armed timer. */
  const runTimer = async () => {
    const entry = timers.entries().next().value;
    if (!entry) return;
    timers.delete(entry[0]);
    await entry[1].fn();
  };
  const runTimerId = async (id) => {
    const t = timers.get(id);
    timers.delete(id);
    if (t) await t.fn();
  };
  return {
    reset,
    events,
    timers,
    runTimer,
    runTimerId,
    reasons,
    release: (reply) => releasePending?.(reply),
    calls: () => call,
  };
}

test("ok commits home once, invalidating before any UI change", async () => {
  const { reset, events } = harness({
    replies: [{ status: "ok", preferences: { autoJoinHypixel: true } }],
  });
  await reset.resetToHome("game_session_ended");
  // The pre-show timer is cancelled before home commits: a reset that beat
  // the gate never flashes transitional copy.
  assert.deepEqual(events, [
    "stop_polling",
    "invalidate",
    "invoke#1",
    "cancel_timer",
    "commit(prefs)",
  ]);
});

test("already_reset commits home from its carried view", async () => {
  const { reset, events } = harness({
    replies: [{ status: "already_reset", preferences: { autoJoinHypixel: false } }],
  });
  await reset.resetToHome("game_session_ended");
  assert.deepEqual(events, [
    "stop_polling",
    "invalidate",
    "invoke#1",
    "cancel_timer",
    "commit(prefs)",
  ]);
});

test("home is refreshed after commit and a refresh failure surfaces", async () => {
  const ok = harness({
    replies: [{ status: "ok", preferences: { autoJoinHypixel: true } }],
    withRefresh: true,
  });
  await ok.reset.resetToHome("game_session_ended");
  assert.deepEqual(ok.events.slice(-2), ["commit(prefs)", "refresh"]);

  const failing = harness({
    replies: [{ status: "ok", preferences: { autoJoinHypixel: true } }],
    withRefresh: true,
    refreshFails: true,
  });
  await failing.reset.resetToHome("game_session_ended");
  // Home is committed FIRST; the failure then reaches the error rendering
  // instead of being swallowed.
  assert.deepEqual(failing.events.slice(-3), [
    "commit(prefs)",
    "refresh",
    "refresh_error(setup)",
  ]);
});

test("busy shows transitional copy then commits on a later attempt", async () => {
  const { reset, events, runTimer } = harness({
    replies: [{ status: "busy" }, { status: "busy" }, { status: "ok" }],
  });
  await reset.resetToHome("game_session_ended");
  assert.deepEqual(events, [
    "stop_polling",
    "invalidate",
    "invoke#1",
    "cancel_timer",
    "transitional(game_session_ended)",
  ]);
  await runTimer();
  assert.equal(events.at(-1), "transitional(game_session_ended)");
  await runTimer();
  assert.equal(events.at(-1), "commit(null)");
  // No half-reset: home committed exactly once, final copy never shown.
  assert.equal(events.filter((e) => e.startsWith("commit")).length, 1);
  assert.equal(events.filter((e) => e.startsWith("final")).length, 0);
});

test("not_terminal takes the same bounded retry branch", async () => {
  const { reset, events, runTimer } = harness({
    replies: [{ status: "not_terminal" }, { status: "already_reset" }],
  });
  await reset.resetToHome("game_session_changed");
  assert.equal(events.at(-1), "transitional(game_session_changed)");
  await runTimer();
  assert.equal(events.at(-1), "commit(null)");
});

test("transport errors retry and permanent failure settles on the final copy", async () => {
  const { reset, events, runTimer, calls } = harness({
    replies: ["throw"],
    maxAttempts: 3,
  });
  await reset.resetToHome("game_session_ended");
  await runTimer();
  await runTimer();
  assert.equal(calls(), 3);
  assert.equal(events.at(-1), "final(game_session_ended)");
  // The final state is reached only after exhausting every attempt.
  assert.equal(events.filter((e) => e.startsWith("transitional")).length, 2);
  assert.equal(events.filter((e) => e.startsWith("commit")).length, 0);
});

test("resetToHome is single-flight while active", async () => {
  const { reset, events, runTimer } = harness({
    replies: [{ status: "busy" }, { status: "ok" }],
  });
  await reset.resetToHome("game_session_ended");
  await reset.resetToHome("game_session_ended");
  assert.equal(events.filter((e) => e === "stop_polling").length, 1);
  await runTimer();
  assert.equal(events.at(-1), "commit(null)");
});

test("cancelRetry stops the pending retry", async () => {
  const { reset, events, runTimer } = harness({
    replies: [{ status: "busy" }, { status: "ok" }],
  });
  await reset.resetToHome("game_session_ended");
  reset.cancelRetry();
  assert.equal(events.includes("cancel_timer"), true);
  // A fired stale timer after cancel commits nothing.
  await runTimer();
  assert.equal(events.filter((e) => e.startsWith("commit")).length, 0);
});

// ── D4a: honest transitional feedback on the happy path ─────────────────

test("transitional copy is shown before a slow reset resolves", async () => {
  const ctx = harness({ replies: ["pending"] });
  const pending = ctx.reset.resetToHome("game_session_ended");
  // The backend has not answered yet and the gate has elapsed.
  assert.deepEqual(ctx.events, ["stop_polling", "invalidate", "invoke#1"]);
  await ctx.runTimer();
  assert.equal(ctx.events.at(-1), "transitional(game_session_ended)");
  // The late success still commits home exactly once.
  ctx.release({ status: "ok", preferences: { autoJoinHypixel: true } });
  await pending;
  assert.equal(ctx.events.at(-1), "commit(prefs)");
  assert.equal(ctx.events.filter((e) => e.startsWith("commit")).length, 1);
});

test("a fast reset (<150ms) never flashes the transitional copy", async () => {
  const { reset, events, timers } = harness({
    replies: [{ status: "ok", preferences: { autoJoinHypixel: true } }],
  });
  await reset.resetToHome("game_session_ended");
  assert.equal(events.filter((e) => e.startsWith("transitional")).length, 0);
  // Nothing is left armed to fire the copy after home is already up.
  assert.equal(timers.size, 0);
});

test("a superseding reset cancels the pending transitional timer", async () => {
  // The host cancel is deliberately leaky here, so only the generation
  // guard can stop reset A's timer from firing into reset B.
  const ctx = harness({
    replies: ["pending", { status: "ok", preferences: { autoJoinHypixel: true } }],
    leakTimers: true,
  });
  ctx.reset.resetToHome("game_session_ended");
  // Timer 1 is reset A's pre-show gate.
  assert.equal(ctx.timers.get(1)?.ms, 150);
  ctx.reset.cancelRetry();
  await ctx.reset.resetToHome("launch_aborted");
  assert.equal(ctx.events.at(-1), "commit(prefs)");
  // Reset A's leaked gate fires after B already committed home: silent.
  await ctx.runTimerId(1);
  assert.equal(ctx.events.filter((e) => e.startsWith("transitional")).length, 0);
});

test("the reset reason is threaded through to the invoker", async () => {
  const ctx = harness({ replies: [{ status: "ok", preferences: {} }] });
  await ctx.reset.resetToHome("launch_aborted");
  assert.deepEqual(ctx.reasons, ["launch_aborted"]);
});

// ── integration: the orchestrator drives the REAL controller, then a
// second launch adopts the fresh backend generation end to end ──────────

test("reset-to-home wiring: invalidate, commit through the controller, fresh second launch", async () => {
  const { createLaunchController } = await import("./launch-controller.js");
  const { CMD } = await import("./tauri-contract.js");
  let generation = 5;
  let resolvePoll;
  let resolveReset;
  const invoke = (cmd, args) => {
    if (cmd === CMD.launchPreferences) {
      return Promise.resolve({
        autoJoinHypixel: true,
        useExternalOverlay: true,
        health: "valid",
      });
    }
    if (cmd === CMD.launchLunar) {
      return Promise.resolve({
        status: "launched",
        generation,
        outcome: {
          autoJoinHypixel: true,
          useExternalOverlay: true,
          action: "game_launch_requested",
        },
      });
    }
    if (cmd === CMD.lobbyState) {
      return new Promise((resolve) => {
        resolvePoll = () =>
          resolve({ kind: "session_ended", reason: "game_session_ended" });
      });
    }
    if (cmd === CMD.resetSessionEnd) {
      return new Promise((resolve) => {
        resolveReset = () =>
          resolve({
            status: "ok",
            preferences: {
              autoJoinHypixel: false,
              useExternalOverlay: false,
              health: "valid",
            },
          });
      });
    }
    return Promise.reject(new Error(`unexpected ${cmd}`));
  };
  const ctrl = createLaunchController(invoke);
  await ctrl.refreshPreferences();
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  assert.equal(ctrl.getState().launchGen, 5);

  const order = [];
  const reset = createSessionReset({
    invokeReset: () => invoke(CMD.resetSessionEnd),
    stopPolling: () => order.push("stop"),
    invalidate: () => {
      order.push("invalidate");
      ctrl.invalidateSession();
    },
    commitHome: (prefs) => {
      order.push("commit");
      ctrl.resetLaunchSession(prefs);
    },
    showTransitional: () => order.push("transitional"),
    showFinal: () => order.push("final"),
    schedule: () => 0,
    cancel: () => {},
  });

  // A lobby poll response lands WHILE the backend reset is still pending:
  // the pre-commit invalidation must orphan it before any UI change.
  const stalePoll = ctrl.pollLobbyOnce({
    onPoll: () => order.push("stale-poll-delivered"),
  });
  const resetting = reset.resetToHome("game_session_ended");
  // stop + invalidate have run; the backend reply has NOT resolved yet.
  assert.deepEqual(order, ["stop", "invalidate"]);
  // Deliver the poll response mid-reset and let it fully settle.
  resolvePoll();
  await stalePoll;
  assert.deepEqual(order, ["stop", "invalidate"], "mid-reset poll orphaned");
  // Now the backend reset resolves and home commits.
  resolveReset();
  await resetting;
  assert.deepEqual(order, ["stop", "invalidate", "commit"]);

  const afterReset = ctrl.getState();
  assert.equal(afterReset.activeOutcome, null);
  assert.equal(afterReset.confirmedAutoJoin, false, "checkboxes resynced from the reset view");
  assert.equal(afterReset.confirmedOverlay, false);

  // Second launch through the SAME controller adopts the new generation.
  generation = 6;
  await ctrl.launch("lunar", { onLaunchReply() {}, onProgressStart() {} });
  const second = ctrl.getState();
  assert.equal(second.launchGen, 6);
  assert.equal(second.activeOutcome?.action, "game_launch_requested");
});
