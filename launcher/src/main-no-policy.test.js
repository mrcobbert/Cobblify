import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

// A9: the launcher draws; it never re-derives a presentation decision. These are
// the exact constructs the pre-v2 renderer used, pinned so they cannot creep back.
const here = path.dirname(fileURLToPath(import.meta.url));
const sources = ["main.js", "row-model.js", "roster-identity.js", "dashboard-view.js", "board-model.js", "board-render.js", "board-layout.js"].map((f) =>
  [f, readFileSync(path.join(here, f), "utf8")],
);

const FORBIDDEN = [
  [/CHEAT_TAG/, "tag-name regex"],
  [/isCheater\s*\(/, "cheater derivation"],
  [/fkdr\s*>=\s*\d/, "FKDR threshold"],
  [/f\s*>=\s*10\s*\?/, "FKDR tier ternary"],
  [/seraphThreat\s*>=\s*[1-9]/, "threat acted on"],
  [/urchinTags|seraphTags/, "v1 tag string lists"],
  [/BLATANT|GHOST|REACH|AUTOCLICK/, "demo tag names"],
];

for (const [file, text] of sources) {
  test(`${file} contains no presentation policy`, () => {
    for (const [re, why] of FORBIDDEN) {
      assert.equal(re.test(text), false, `${file}: ${why} (${re})`);
    }
  });
}

test("the board draws rows only through the row model", () => {
  const [, model] = sources.find(([f]) => f === "board-model.js");
  assert.match(model, /import \{ colorClass, rowModel, sectionSpans, tierOf \} from "\.\/row-model\.js"/);
  assert.match(model, /const m = rowModel\(p\);/);
  const [, main] = sources.find(([f]) => f === "main.js");
  assert.doesNotMatch(main, /rowModel|sectionSpans|sortRoster|rankedOpponentTeams/, "main.js hands snapshots to board-model.js");
});
