import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

import { baseVersion, channelOf, fmtDate, noteFor, whatsNewItems, whatsNewNotes } from "./ui/notes.js";

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
  assert.equal(NOTES.find((n) => n.channel === "stable").version, "0.19.0", "dev notes may sit above it");
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

const note = (version, channel = "dev") => ({ version, channel, date: "2026-11-17", sections: [["New", [version]]] });
const LINE = [note("0.19.0-dev.95"), note("0.19.0-dev.93"), note("0.19.0", "stable"), note("0.18.2-dev.61"), note("0.18.2", "stable")];
const versions = (list) => list.map((n) => n.version);

test("a dev build after one of its own line lists only that line's notes since the last one opened", () => {
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0-dev.95", "0.19.0-dev.94")), ["0.19.0-dev.95"]);
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0-dev.95", "0.19.0-dev.92")), ["0.19.0-dev.95", "0.19.0-dev.93"], "skipped builds' notes too, newest first");
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0-dev.94", "0.19.0-dev.92")), ["0.19.0-dev.93"], "never a later build's note");
});

test("a dev build without a note of its own shows nothing, never the release's note again", () => {
  assert.deepEqual(whatsNewNotes(LINE, "0.19.0-dev.97", "0.19.0-dev.95"), []);
  // the bundled notes: dev.92 after dev.91 does not repeat 0.19.0's Ornithe note
  assert.deepEqual(whatsNewNotes(NOTES, "0.19.0-dev.92", "0.19.0-dev.91"), []);
});

test("the first build of a line, a release and an unknown previous read the release's note", () => {
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0-dev.93", "0.18.2-dev.61")), ["0.19.0-dev.93"], "its own note");
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0-dev.94", "0.18.2")), ["0.19.0"], "else its release's");
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0", "0.19.0-dev.95")), ["0.19.0"]);
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0-dev.94", null)), ["0.19.0"]);
  assert.deepEqual(versions(whatsNewNotes(LINE, "0.19.0-next.2", "0.19.0-dev.95")), ["0.19.0"], "another line's previous");
  assert.deepEqual(whatsNewNotes(LINE, "9.9.9", "0.19.0"), []);
});
