import test from "node:test";
import assert from "node:assert/strict";
import {
  CONNECTION_COPY,
  FORBIDDEN_PRE_LIVE,
  manualRouteSubtitleFor,
} from "./connection-view.js";

test("generic manual shell copy avoids Hypixel before live", () => {
  assert.doesNotMatch(CONNECTION_COPY.manual.subtitle, FORBIDDEN_PRE_LIVE);
});

test("launch-aborted copy is pre-live safe and says nothing about the network", () => {
  for (const mode of ["launch_aborted", "launch_aborted_resetting"]) {
    const copy = CONNECTION_COPY[mode];
    assert.doesNotMatch(copy.title, FORBIDDEN_PRE_LIVE, mode);
    assert.doesNotMatch(copy.subtitle, FORBIDDEN_PRE_LIVE, mode);
  }
  // Same voice as session_ended / session_ended_resetting.
  assert.equal(
    CONNECTION_COPY.launch_aborted.subtitle,
    CONNECTION_COPY.session_ended.subtitle,
  );
  assert.equal(
    CONNECTION_COPY.launch_aborted_resetting.subtitle,
    CONNECTION_COPY.session_ended_resetting.subtitle,
  );
  assert.equal(CONNECTION_COPY.launch_aborted_resetting.loading, true);
  assert.equal(CONNECTION_COPY.launch_aborted.loading, false);
});

test("prism-off production manual subtitle avoids Hypixel before live", () => {
  const subtitle = manualRouteSubtitleFor("game_launch_requested");
  assert.doesNotMatch(subtitle, FORBIDDEN_PRE_LIVE);
  assert.equal(subtitle, "Prism is launching — use Minecraft when ready.");
});

test("lunar-off production manual subtitle avoids Hypixel before live", () => {
  const subtitle = manualRouteSubtitleFor("launcher_opened");
  assert.doesNotMatch(subtitle, FORBIDDEN_PRE_LIVE);
  assert.match(subtitle, /Lunar/i);
});
