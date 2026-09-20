import test from "node:test";
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  CONTRACT_VERSION,
  classifyLive,
  isOutdatedSnapshot,
  isValidLobbySnapshot,
} from "./lobby-validator.js";

// A8: the fixtures under common/src/test/resources/lobby-contract are shared with the
// mod's DTO test (Java) and the Rust validator; all three must agree on them.
const DIR = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../common/src/test/resources/lobby-contract",
);

function fixtures(sub) {
  const dir = path.join(DIR, sub);
  const names = readdirSync(dir).filter((f) => f.endsWith(".json")).sort();
  assert.ok(names.length > 0, `no fixtures in ${dir}`);
  return names.map((name) => [name, JSON.parse(readFileSync(path.join(dir, name), "utf8"))]);
}

test("every valid v2 fixture is accepted and current", () => {
  for (const [name, snap] of fixtures("valid")) {
    assert.ok(isValidLobbySnapshot(snap), `valid/${name} rejected`);
    assert.equal(snap.v, CONTRACT_VERSION, `valid/${name} version`);
    assert.equal(isOutdatedSnapshot(snap), false, `valid/${name} outdated`);
    assert.notEqual(classifyLive(snap), "invalid", `valid/${name} classify`);
  }
});

test("every invalid fixture is rejected", () => {
  for (const [name, snap] of fixtures("invalid")) {
    assert.equal(isValidLobbySnapshot(snap), false, `invalid/${name} accepted`);
    assert.equal(classifyLive(snap), "invalid", `invalid/${name} classify`);
  }
});

test("a legacy v1 fixture is valid, live, and outdated", () => {
  for (const [name, snap] of fixtures("valid-v1")) {
    assert.ok(isValidLobbySnapshot(snap), `valid-v1/${name} rejected`);
    assert.equal(snap.v, 1, `valid-v1/${name} version`);
    assert.ok(isOutdatedSnapshot(snap), `valid-v1/${name} not outdated`);
    assert.equal(classifyLive(snap), "live");
  }
});

test("the full fixture exercises every row state and both chip classes", () => {
  const [, full] = fixtures("valid").find(([name]) => name === "full.json");
  const states = new Set(full.players.map((p) => p.state));
  for (const s of ["OK", "LOADING", "NICKED", "NEVER_PLAYED", "ERROR"]) assert.ok(states.has(s), s);
  const chips = full.players.flatMap((p) => p.chips);
  assert.ok(chips.some((c) => c.positive === true));
  assert.ok(chips.some((c) => c.positive === false));
  assert.ok(full.players.some((p) => p.badge === null));
  assert.ok(full.players.some((p) => p.badge && p.cheater === true));
});
