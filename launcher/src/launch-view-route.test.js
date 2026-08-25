import test from "node:test";
import assert from "node:assert/strict";

import {
  launchViewRoute,
  keepsStageNarration,
  showsCancelLaunch,
} from "./launch-view-route.js";

const ACTIONS = ["game_launch_requested", "launcher_opened", "launch_unconfirmed"];
const BOOLS = [true, false];

function rows() {
  const out = [];
  for (const action of ACTIONS) {
    for (const autoJoinHypixel of BOOLS) {
      for (const useExternalOverlay of BOOLS) {
        out.push({ action, autoJoinHypixel, useExternalOverlay });
      }
    }
  }
  return out;
}

test("the full action x auto-join x overlay table routes exactly one way", () => {
  const seen = rows().map((outcome) => [
    `${outcome.action}/${outcome.autoJoinHypixel}/${outcome.useExternalOverlay}`,
    launchViewRoute(outcome),
  ]);
  assert.equal(seen.length, 12);
  for (const [label, route] of seen) {
    const action = label.split("/")[0];
    if (action === "game_launch_requested") {
      assert.deepEqual(
        route,
        { stayHome: true, shellMode: null, subtitleKind: null },
        label,
      );
    } else {
      assert.deepEqual(
        route,
        { stayHome: false, shellMode: "manual", subtitleKind: action },
        label,
      );
    }
  }
});

test("every confirmed dispatch stays home, auto-join on included", () => {
  for (const useExternalOverlay of BOOLS) {
    const on = launchViewRoute({
      action: "game_launch_requested",
      autoJoinHypixel: true,
      useExternalOverlay,
    });
    const off = launchViewRoute({
      action: "game_launch_requested",
      autoJoinHypixel: false,
      useExternalOverlay,
    });
    assert.equal(on.stayHome, true);
    // Auto-join ON is standardized onto the auto-join OFF pattern.
    assert.deepEqual(on, off);
  }
});

test("manual recovery routes carry their own subtitle kind", () => {
  assert.equal(
    launchViewRoute({ action: "launcher_opened", autoJoinHypixel: false }).subtitleKind,
    "launcher_opened",
  );
  assert.equal(
    launchViewRoute({ action: "launch_unconfirmed", autoJoinHypixel: false }).subtitleKind,
    "launch_unconfirmed",
  );
});

test("unknown and missing actions take the safe home route", () => {
  assert.deepEqual(launchViewRoute({ action: "some_future_action" }), {
    stayHome: true,
    shellMode: null,
    subtitleKind: null,
  });
  assert.equal(launchViewRoute(undefined).stayHome, true);
});

test("cancel launch is offered only in the dispatched-but-not-live window", () => {
  const live = { action: "game_launch_requested" };
  const base = {
    activeOutcome: live,
    launchPhase: "loading",
    homeUntilLive: true,
    resetActive: false,
  };
  // The window itself: dispatched, held on home, no reset running.
  assert.equal(showsCancelLaunch(base), true);
  // Settled but still pre-live is the same window - the quit that wedged
  // the app happens here too.
  assert.equal(showsCancelLaunch({ ...base, launchPhase: "done" }), true);
  // Idle home: nothing to cancel.
  assert.equal(
    showsCancelLaunch({ ...base, activeOutcome: null, launchPhase: "idle" }),
    false,
  );
  assert.equal(showsCancelLaunch({ ...base, launchPhase: "idle" }), false);
  // Post-live: the marker is cleared when the dashboard opens.
  assert.equal(showsCancelLaunch({ ...base, homeUntilLive: false }), false);
  // A reset already in flight owns the surface.
  assert.equal(showsCancelLaunch({ ...base, resetActive: true }), false);
});

test("stage narration is preserved only for the loading kind that owns it", () => {
  assert.equal(
    keepsStageNarration({ launchPhase: "loading", narratingKind: "lunar", kind: "lunar" }),
    true,
  );
  assert.equal(
    keepsStageNarration({ launchPhase: "loading", narratingKind: "lunar", kind: "forge" }),
    false,
  );
  assert.equal(
    keepsStageNarration({ launchPhase: "done", narratingKind: "lunar", kind: "lunar" }),
    false,
  );
  assert.equal(
    keepsStageNarration({ launchPhase: "loading", narratingKind: null, kind: "lunar" }),
    false,
  );
});
