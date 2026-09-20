import test from "node:test";
import assert from "node:assert/strict";

import { colorClass, rowCells, rowChips, rowClasses, rowKind, rowModel, sectionSpans, tierOf } from "./row-model.js";

// A9: the row is drawn from exported decisions only. Nothing here derives a tier,
// a cheater flag or a badge from stats or tag names.

const chip = (code, color, label, positive = false) => ({ code, color, label, positive });

const base = {
  name: "P",
  state: "OK",
  nicked: false,
  realName: null,
  rank: "",
  rankCodes: "",
  mode: "Overall",
  fkdr: 1.234,
  wlr: 2,
  finalKills: 12345,
  kd: 0.5,
  fkdrTier: 0,
  cheater: false,
  badge: null,
  chips: [],
  seraphThreat: -1,
};

test("tier class is the exported tier, never derived from the FKDR value", () => {
  assert.deepEqual(rowClasses({ ...base, fkdr: 99, fkdrTier: 0 }), ["prow", "t0"]);
  assert.deepEqual(rowClasses({ ...base, fkdr: 0.1, fkdrTier: 3 }), ["prow", "t3"]);
  assert.equal(tierOf({ fkdrTier: 7 }), 3);
  assert.equal(tierOf({ fkdrTier: -2 }), 0);
  assert.equal(tierOf({}), 0);
});

test("cheater class follows the exported flag, not the chips", () => {
  const sniper = { ...base, chips: [chip("S", "§4", "Sniper")], badge: chip("S", "§4", "Sniper") };
  assert.deepEqual(rowClasses(sniper), ["prow", "t0"]);
  assert.deepEqual(rowClasses({ ...sniper, cheater: true }), ["prow", "t0", "cheater"]);
  assert.deepEqual(rowClasses({ ...base, cheater: true, seraphThreat: 5 }), ["prow", "t0", "cheater"]);
});

test("the priority badge leads as its glyph, then every tag label, each in its colour", () => {
  const p = {
    ...base,
    chips: [chip("BC", "§6", "Blatant Cheater"), chip("SL", "§a", "Safelisted", true)],
    badge: chip("BC", "§6", "Blatant Cheater"),
  };
  assert.deepEqual(rowChips(p), [
    { cls: "badge cheat", text: "BC", color: "§6" },
    { cls: "cheat", text: "Blatant Cheater", color: "§6" },
    { cls: "safe", text: "Safelisted", color: "§a" },
  ]);
  // A positive badge (safelist alone) is drawn as a safe glyph.
  const safe = { ...base, chips: [chip("SL", "§a", "Safelisted", true)], badge: chip("SL", "§a", "Safelisted", true) };
  assert.equal(rowChips(safe)[0].cls, "badge safe");
  // The A7 fixture: Sniper + Caution + Safelisted → badge S in §4, three labels in their colours.
  const golden = {
    ...base,
    fkdrTier: 2,
    badge: chip("S", "§4", "Sniper"),
    chips: [chip("S", "§4", "Sniper"), chip("C", "§6", "Caution"), chip("SL", "§a", "Safelisted", true)],
  };
  assert.deepEqual(rowChips(golden), [
    { cls: "badge cheat", text: "S", color: "§4" },
    { cls: "cheat", text: "Sniper", color: "§4" },
    { cls: "cheat", text: "Caution", color: "§6" },
    { cls: "safe", text: "Safelisted", color: "§a" },
  ]);
  // A null badge draws no glyph.
  assert.deepEqual(rowChips({ ...base, badge: null, chips: [chip("C", "§6", "Caution")] }), [
    { cls: "cheat", text: "Caution", color: "§6" },
  ]);
});

test("exported colours become chip classes so unverified and verified blacklists differ", () => {
  assert.equal(colorClass("§4"), "mc-4");
  assert.equal(colorClass("§c"), "mc-c");
  assert.equal(colorClass("§A"), "mc-a");
  assert.equal(colorClass(""), "");
  assert.equal(colorClass(undefined), "");
  assert.equal(colorClass("§z"), "");
  assert.notEqual(colorClass("§4"), colorClass("§c"));
});

test("the mode the numbers came from is shown when it is not Overall", () => {
  assert.deepEqual(rowChips({ ...base, mode: "4s" }), [{ cls: "mode", text: "4s stats" }]);
  assert.deepEqual(rowChips({ ...base, mode: "Overall" }), []);
  assert.deepEqual(rowChips({ ...base, mode: "" }), []);
  // Order: badge, labels, mode, threat.
  const all = { ...base, mode: "Solo", seraphThreat: 2, badge: chip("BOT", "§e", "Bot"), chips: [chip("BOT", "§e", "Bot")] };
  assert.deepEqual(
    rowChips(all).map((c) => c.cls),
    ["badge cheat", "cheat", "mode", "neutral"],
  );
  // Never on a row without numbers.
  assert.deepEqual(rowChips({ ...base, state: "NICKED", nicked: true, mode: "4s" }), [{ cls: "nick", text: "Nicked" }]);
});

test("a known threat level is a neutral chip, never an alarm", () => {
  assert.deepEqual(rowChips({ ...base, seraphThreat: 4 }), [{ cls: "neutral", text: "threat 4" }]);
  assert.deepEqual(rowChips({ ...base, seraphThreat: -1 }), []);
  assert.deepEqual(rowClasses({ ...base, seraphThreat: 4 }), ["prow", "t0"]);
});

test("ERROR renders exactly like LOADING", () => {
  const loading = rowModel({ ...base, state: "LOADING" });
  const error = rowModel({ ...base, state: "ERROR" });
  assert.deepEqual(error, loading);
  assert.equal(loading.kind, "loading");
  assert.deepEqual(loading.classes, ["prow", "loading"]);
  assert.deepEqual(loading.chips, [{ cls: "state", text: "resolving" }]);
  assert.ok(loading.cells.every((c) => c.skeleton));
});

test("an unrevealed nick reads Nicked with question-mark cells", () => {
  const m = rowModel({ ...base, state: "NICKED", nicked: true });
  assert.equal(m.kind, "nicked");
  assert.deepEqual(m.chips, [{ cls: "nick", text: "Nicked" }]);
  assert.deepEqual(
    m.cells.map((c) => c.text),
    ["?", "?", "?", "?"],
  );
  assert.ok(m.cells.every((c) => c.muted));
});

test("a revealed nick shows the real name next to the real account's numbers", () => {
  const m = rowModel({ ...base, nicked: true, realName: "RealGuy", fkdr: 7, fkdrTier: 2 });
  assert.equal(m.kind, "stats");
  assert.deepEqual(m.chips, [{ cls: "nick", text: "nick→RealGuy" }]);
  assert.equal(m.cells[0].text, "7.00");
  assert.deepEqual(m.classes, ["prow", "t2"]);
  // Still revealed while the real account resolves.
  const loading = rowModel({ ...base, state: "LOADING", nicked: true, realName: "RealGuy" });
  assert.deepEqual(loading.chips, [
    { cls: "state", text: "resolving" },
    { cls: "nick", text: "nick→RealGuy" },
  ]);
});

test("never played renders dashes", () => {
  const m = rowModel({ ...base, state: "NEVER_PLAYED" });
  assert.equal(m.kind, "unresolved");
  assert.deepEqual(m.chips, [{ cls: "state", text: "never played" }]);
  assert.deepEqual(
    m.cells.map((c) => c.text),
    ["—", "—", "—", "—"],
  );
});

test("stat cells format the exported numbers", () => {
  assert.deepEqual(
    rowCells(base).map((c) => [c.cls, c.text]),
    [
      ["fkdr", "1.23"],
      ["cell-wlr", "2.00"],
      ["cell-finals", (12345).toLocaleString()],
      ["cell-kd", "0.50"],
    ],
  );
});

test("presence leads the chips and dims the row", () => {
  const out = rowModel({ ...base, presence: "ELIMINATED", chips: [chip("SL", "§a", "Safelisted", true)] });
  assert.deepEqual(out.classes, ["prow", "t0", "is-out", "is-eliminated"]);
  assert.deepEqual(out.chips[0], { cls: "out", text: "OUT" });
  assert.equal(out.chips[1].cls, "safe");
  assert.deepEqual(rowClasses({ ...base, presence: "DISCONNECTED" }), ["prow", "t0", "is-out"]);
  assert.equal(rowKind({ ...base, presence: "MISSING" }), "stats");
});

test("rank colour codes become classes and a custom plus colour survives", () => {
  assert.deepEqual(sectionSpans("§b[MVP§c+§b]"), [
    { cls: "mc-b", text: "[MVP" },
    { cls: "mc-c", text: "+" },
    { cls: "mc-b", text: "]" },
  ]);
  assert.deepEqual(sectionSpans("§6[MVP§c++§6]"), [
    { cls: "mc-6", text: "[MVP" },
    { cls: "mc-c", text: "++" },
    { cls: "mc-6", text: "]" },
  ]);
  assert.deepEqual(sectionSpans("§l§a[VIP]§r x"), [
    { cls: "mc-a", text: "[VIP]" },
    { cls: "", text: " x" },
  ]);
  assert.deepEqual(sectionSpans(""), []);
  assert.deepEqual(sectionSpans(null), []);
  assert.deepEqual(sectionSpans("plain"), [{ cls: "", text: "plain" }]);
});
