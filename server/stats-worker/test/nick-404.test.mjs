/**
 * Pure unit tests for how {@link scrapePlayerHtml} classifies hypixel's player-page responses.
 *
 * Regression origin (2026-08-14): a nicked player is a 404 on hypixel.net/player/<nick>, and that
 * 404 page is ~42 KB and carries Cloudflare's `/cdn-cgi/challenge-platform/` beacon script — as
 * does EVERY Cloudflare-proxied page, successful ones included. The block heuristic
 * (`html.length < 50_000 && CHALLENGE_RE.test(html)`) therefore matched every nick, which:
 *   1. returned state ERROR, which the mod renders as a blank tab cell (not "[Nicked]"), and
 *   2. called writeBlocked() — tripping the GLOBAL 120 s origin breaker for every user of the
 *      shared worker, off one nicked player in one lobby.
 *
 * Measured against the live site while diagnosing: the 404 page is 42,611-42,979 bytes, and a real
 * player page reads a ~55.5 KB prefix (needle at ~33.5 KB + 22 KB cushion) — only ~5.4 KB above the
 * same 50 KB gate, so the beacon token was one markup trim away from blanking successful lookups
 * too. Hence the token is gone from CHALLENGE_RE and status is decided before content.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { scrapePlayerHtml } from "../src/scrape.js";

/** Cloudflare's beacon script tag, present on every proxied hypixel.net response. */
const CF_BEACON =
  '<script src="/cdn-cgi/challenge-platform/h/g/scripts/jsd/main.js"></script>';

/**
 * A page of `bytes` total length carrying the beacon — mimics the real 404's size class.
 * `forumError` adds XenForo's error-template marker, which on the live site sits at byte ~92 and
 * is what proves the forum application (rather than something in front of it) produced the 404.
 */
function beaconPage(bytes, { inner = "", forumError = false } = {}) {
  const head = `<html ${forumError ? 'data-template="error" ' : ""}>` +
    `<head><title>Oops! We ran into some problems. | Hypixel Forums</title></head>` +
    `<body>${inner}${CF_BEACON}`;
  const tail = "</body></html>";
  return head + "<!--" + "p".repeat(Math.max(0, bytes - head.length - tail.length - 7)) + "-->" + tail;
}

/** A genuine Cloudflare interstitial. */
const CHALLENGE_PAGE =
  `<html><head><title>Just a moment...</title></head><body>${CF_BEACON}</body></html>`;

function stubFetch(status, body) {
  globalThis.fetch = async () =>
    new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8" } });
}

test("a 404 player page is NICKED, not a cloudflare block", async () => {
  const page = beaconPage(42_600, { forumError: true });
  assert.ok(page.length < 50_000, "fixture must sit under the size gate, like the real 404");
  stubFetch(404, page);

  const r = await scrapePlayerHtml("LazyAndTiny", {});
  assert.equal(r.ok, false);
  assert.equal(r.body.state, "NICKED");
  assert.equal(r.body.displayName, "LazyAndTiny");
  assert.equal(r.body.httpStatus, 404);
  // The absence of this field is what keeps writeBlocked() — the global breaker — untouched.
  assert.equal(r.body.error, undefined, "a nick must never be reported as blocked_by_cloudflare");
});

// The other half of the verdict: a 404 the forum did NOT produce is infrastructure trouble
// (Cloudflare, a proxy, a moved route), and calling that "nicked" would confidently mislabel every
// player at once. It stays a retryable ERROR, exactly as before the nick verdict existed.
test("a 404 without the forum error template is http_404, not NICKED", async () => {
  stubFetch(404, beaconPage(42_600)); // no forumError marker

  const r = await scrapePlayerHtml("LazyAndTiny", {});
  assert.equal(r.ok, false);
  assert.equal(r.body.state, "ERROR");
  assert.equal(r.body.error, "http_404");
  assert.notEqual(r.body.state, "NICKED");
});

test("a bare Cloudflare-style 404 is never read as a nick", async () => {
  // No XenForo markup at all — an edge/proxy 404, the shape a moved route would produce.
  stubFetch(404, "<html><head><title>404 Not Found</title></head><body>nginx</body></html>");

  const r = await scrapePlayerHtml("SomeGuy", {});
  assert.equal(r.body.state, "ERROR");
  assert.equal(r.body.error, "http_404");
});

test("the cloudflare beacon alone never makes a page look like a challenge", async () => {
  // A short 2xx body carrying only the beacon: content-wise indistinguishable from the old
  // false positive, but it is not an interstitial and must not be treated as one.
  stubFetch(200, beaconPage(10_000, { inner: "<div>short but real</div>" }));

  const r = await scrapePlayerHtml("SomeGuy", {});
  assert.equal(r.ok, true, "beacon + small body is not evidence of a block");
});

test("a genuine challenge page is still blocked", async () => {
  stubFetch(503, CHALLENGE_PAGE);
  const r = await scrapePlayerHtml("SomeGuy", {});
  assert.equal(r.ok, false);
  assert.equal(r.body.error, "blocked_by_cloudflare");
});

test("a 403 is still blocked regardless of body", async () => {
  stubFetch(403, beaconPage(10_000));
  const r = await scrapePlayerHtml("SomeGuy", {});
  assert.equal(r.ok, false);
  assert.equal(r.body.error, "blocked_by_cloudflare");
});

test("other non-2xx statuses keep their http_<code> classification", async () => {
  stubFetch(500, beaconPage(10_000));
  const r = await scrapePlayerHtml("SomeGuy", {});
  assert.equal(r.ok, false);
  assert.equal(r.body.error, "http_500");
});
