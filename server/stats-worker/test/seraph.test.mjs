import test from "node:test";
import assert from "node:assert/strict";
import {
  normalizeUuid,
  sanitizeText,
  mapTags,
  mapStatistics,
  resultFields,
} from "../src/seraph.js";

const NOW = 1_800_000_000_000;

test("normalizeUuid canonicalizes dashed/undashed/case, rejects junk", () => {
  const dashed = "069A79F4-44e9-4726-A5BE-fca90e38aaf5";
  assert.equal(normalizeUuid(dashed), "069a79f444e94726a5befca90e38aaf5");
  assert.equal(normalizeUuid("069a79f444e94726a5befca90e38aaf5"), "069a79f444e94726a5befca90e38aaf5");
  assert.equal(normalizeUuid("not-a-uuid"), null);
  assert.equal(normalizeUuid(""), null);
  assert.equal(normalizeUuid(null), null);
  assert.equal(normalizeUuid("069a79f444e94726a5befca90e38aaf5aa"), null);
});

test("sanitizeText strips section signs and control chars, caps length", () => {
  assert.equal(sanitizeText("§cred §lstuff"), "red stuff");
  assert.equal(sanitizeText("x".repeat(500)).length, 120);
  assert.equal(sanitizeText(null), "");
});

test("mapTags: blacklist tagged -> cheater tag w/ subtype + verified; reason sanitized", () => {
  const tags = mapTags(
    {
      blacklist: { tagged: true, report_type: "CHEATER", reason: "§4autoclicker", timestamp: 5, verified: true },
    },
    NOW
  );
  assert.equal(tags.length, 1);
  assert.equal(tags[0].kind, "blacklist");
  assert.equal(tags[0].subtype, "cheater");
  assert.equal(tags[0].reason, "autoclicker");
  assert.equal(tags[0].addedOn, 5);
  assert.equal(tags[0].verified, true);
});

test("mapTags: all four lists surface (blacklist/safelist/bot/annoy); order stable", () => {
  const tags = mapTags(
    {
      blacklist: { tagged: true, report_type: "sniper" },
      safelist: { tagged: true, tooltip: "trusted", time_added: 3 },
      bot: { tagged: true, tooltip: "bot acct" },
      annoylist: { tagged: true, tooltip: "annoying" },
      member: { tagged: true }, // dropped
    },
    NOW
  );
  assert.deepEqual(tags.map((t) => t.kind), ["blacklist", "safelist", "bot", "annoy"]);
  assert.equal(tags[1].reason, "trusted");
  assert.equal(tags[1].addedOn, 3);
});

test("mapTags: untagged / tagged:false / missing -> [] (no false accusation)", () => {
  assert.deepEqual(mapTags({ blacklist: { tagged: false } }, NOW), []);
  assert.deepEqual(mapTags({ member: { tagged: true } }, NOW), []); // member is not surfaced
  assert.deepEqual(mapTags({}, NOW), []);
  assert.deepEqual(mapTags(null, NOW), []);
});

test("mapStatistics: threat_level/encounters parsed; junk/absent -> null", () => {
  assert.deepEqual(mapStatistics({ statistics: { threat_level: 7, encounters: 4 } }), { threatLevel: 7, encounters: 4 });
  assert.deepEqual(mapStatistics({ statistics: { threat_level: "x" } }), { threatLevel: null, encounters: null });
  assert.deepEqual(mapStatistics({}), { threatLevel: null, encounters: null });
  assert.deepEqual(mapStatistics(null), { threatLevel: null, encounters: null });
});

test("resultFields wire shapes: ok+tags+stats / ok-empty / notfound / unavailable / missing", () => {
  const tagged = resultFields(
    {
      state: "ok",
      tags: [{ kind: "blacklist", subtype: "cheater", reason: "r", addedOn: 3, verified: true }],
      threatLevel: 8,
      encounters: 2,
    },
    "069a79f444e94726a5befca90e38aaf5"
  );
  assert.equal(tagged.seraphChecked, true);
  assert.equal(tagged.seraphUnavailable, undefined);
  assert.equal(tagged.seraphUuid, "069a79f444e94726a5befca90e38aaf5");
  assert.deepEqual(tagged.seraph.tags, [{ kind: "blacklist", subtype: "cheater", reason: "r", addedOn: 3, verified: true }]);
  assert.equal(tagged.seraph.threatLevel, 8);
  assert.equal(tagged.seraph.encounters, 2);

  // known-empty (no tags, no stats): resolved but no seraph payload
  const empty = resultFields({ state: "ok", tags: [], threatLevel: null, encounters: null }, "u");
  assert.deepEqual(empty, { seraphUuid: "u", seraphChecked: true });

  // stats-only (encounters but no tags) still emits a seraph payload
  const statsOnly = resultFields({ state: "ok", tags: [], threatLevel: null, encounters: 5 }, "u");
  assert.equal(statsOnly.seraph.encounters, 5);
  assert.equal(statsOnly.seraph.tags, undefined);

  const nf = resultFields({ state: "notfound", tags: [] }, "u");
  assert.deepEqual(nf, { seraphUuid: "u", seraphChecked: true, seraphNotFound: true });

  const unavail = resultFields({ state: "unavailable", tags: [] }, "u");
  assert.deepEqual(unavail, { seraphUuid: "u", seraphUnavailable: true });

  // unverified/no-subtype tag omits those flags (no noise).
  const plain = resultFields(
    { state: "ok", tags: [{ kind: "safelist", subtype: "", reason: "", addedOn: 0, verified: false }] },
    "u"
  );
  assert.ok(!("verified" in plain.seraph.tags[0]));
  assert.ok(!("subtype" in plain.seraph.tags[0]));

  assert.deepEqual(resultFields(undefined, "u"), {}); // transient failure -> no metadata
});
