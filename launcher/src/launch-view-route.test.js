import test from "node:test";
import assert from "node:assert/strict";

import {
  launchViewRoute,
  keepsStageNarration,
} from "./launch-view-route.js";

const ACTIONS = ["game_launch_requested", "launcher_opened", "launch_unconfirmed"];
const BOOLS = [true, false];

function rows() {
  const out = [];
  for (const action of ACTIONS) {
    for (const useExternalOverlay of BOOLS) {
      out.push({ action, useExternalOverlay });
    }
  }
  return out;
}

test("the full action x overlay table routes exactly one way", () => {
  const seen = rows().map((outcome) => [
    `${outcome.action}/${outcome.useExternalOverlay}`,
    launchViewRoute(outcome),
  ]);
  assert.equal(seen.length, 6);
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

test("every confirmed dispatch stays home, whatever the overlay", () => {
  for (const useExternalOverlay of BOOLS) {
    const route = launchViewRoute({ action: "game_launch_requested", useExternalOverlay });
    assert.equal(route.stayHome, true);
  }
});

test("manual recovery routes carry their own subtitle kind", () => {
  assert.equal(
    launchViewRoute({ action: "launcher_opened" }).subtitleKind,
    "launcher_opened",
  );
  assert.equal(
    launchViewRoute({ action: "launch_unconfirmed" }).subtitleKind,
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
