import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { baseVersion, channelOf, fmtDate, noteFor, whatsNewItems } from "./ui/notes.js";

const NOTES = JSON.parse(readFileSync(new URL("./release-notes.json", import.meta.url), "utf8"));

test("the bundled notes are well formed and newest first", () => {
  assert.ok(NOTES.length >= 2);
  for (const n of NOTES) {
    assert.match(n.version, /^\d+\.\d+\.\d+(-[a-z]+\.\d+)?$/);
    assert.ok(["stable", "dev"].includes(n.channel));
    assert.ok(fmtDate(n.date), n.version);
    assert.ok(n.sections.length >= 1);
    for (const [h, items] of n.sections) {
      assert.equal(typeof h, "string");
      assert.ok(items.length >= 1);
    }
  }
  assert.equal(NOTES[0].version, "0.18.1");
  const dates = NOTES.map((n) => n.date);
  assert.deepEqual([...dates].sort().reverse(), dates, "dates run newest first");
});

test("a next or dev build reads its release's note", () => {
  assert.equal(baseVersion("0.18.0-next.3"), "0.18.0");
  assert.equal(noteFor(NOTES, "0.18.0-next.3").version, "0.18.0");
  assert.equal(noteFor(NOTES, "0.17.0-dev.32").version, "0.17.0-dev.32");
  assert.equal(noteFor(NOTES, "9.9.9"), null);
});

test("channel words", () => {
  assert.equal(channelOf("0.18.0"), "stable");
  assert.equal(channelOf("0.18.0-dev.4"), "dev");
  assert.equal(channelOf("0.18.0-next.1"), "next");
});

test("What's New never throws on a one-section note", () => {
  assert.deepEqual(whatsNewItems({ sections: [["Fixes", ["a"]]] }), ["a"]);
  assert.deepEqual(whatsNewItems({ sections: [["New", ["a", "b"]], ["Fixes", ["c", "d", "e"]]] }), ["a", "b", "c", "d"]);
  assert.deepEqual(whatsNewItems(null), []);
});

test("dates read as the game writes them", () => {
  assert.equal(fmtDate("2026-10-20"), "20 October 2026");
  assert.equal(fmtDate("2026-10-20", true), "20 Oct 2026");
});
