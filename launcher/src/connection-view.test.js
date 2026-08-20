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
