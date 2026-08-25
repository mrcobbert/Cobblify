import test from "node:test";
import assert from "node:assert/strict";

import { LOBBY_POLL_MS } from "./poll-config.js";

test("the lobby poll cadence stays pinned at its budgeted value", () => {
  // The whole quit-to-home and disconnect-to-UI budget is quantized at this
  // interval; changing it silently changes every latency promise the plan
  // makes, so the value itself is the contract.
  assert.equal(LOBBY_POLL_MS, 250);
});
