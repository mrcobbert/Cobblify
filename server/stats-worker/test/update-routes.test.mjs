import test from "node:test";
import assert from "node:assert/strict";

import {
  cleanupUpdateEvents,
  handleUpdateDownload,
  handleUpdateEvent,
  handleUpdateMetadata,
} from "../src/update-routes.js";

const manifest = {
  schema: 1,
  version: "0.10.0",
  pubDate: "2026-08-25T12:00:00Z",
  notes: "A calmer update flow.",
  releaseNotesUrl: "https://github.com/example/releases/tag/v0.10.0",
  minimumSupportedVersion: "0.9.0",
  policy: "c2lnbmVkLXBvbGljeQ",
  policySignature: "trusted-policy-signature",
  platforms: {
    "darwin-universal": {
      key: "releases/0.10.0/cobblify.app.tar.gz",
      signature: "trusted-artifact-signature",
      sha256: "a".repeat(64),
      sizeBytes: 12,
    },
  },
};

class FakeBucket {
  constructor() {
    this.objects = new Map([
      ["channels/stable.json", JSON.stringify(manifest)],
      ["releases/0.10.0/cobblify.app.tar.gz", "update-bytes"],
    ]);
  }
  async get(key) {
    const value = this.objects.get(key);
    if (value == null) return null;
    return {
      size: new TextEncoder().encode(value).byteLength,
      body: new Response(value).body,
      httpEtag: '"etag"',
      writeHttpMetadata(headers) { headers.set("content-type", key.endsWith(".json") ? "application/json" : "application/octet-stream"); },
      async text() { return value; },
    };
  }
}

function env() {
  return { UPDATE_BUCKET: new FakeBucket(), UPDATE_URL_SECRET: "ticket-secret-that-is-long-enough" };
}

test("current clients receive 204 while older clients receive signed platform metadata", async () => {
  const current = await handleUpdateMetadata(
    new Request("https://worker/launcher/update/darwin/universal/0.10.0"), env(),
    { identity: "friend" }, 1_000,
  );
  assert.equal(current.status, 204);

  const response = await handleUpdateMetadata(
    new Request("https://worker/launcher/update/darwin/universal/0.9.1"), env(),
    { identity: "friend" }, 1_000,
  );
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.version, "0.10.0");
  assert.equal(body.signature, "trusted-artifact-signature");
  assert.equal(body.minimumSupportedVersion, "0.9.0");
  assert.match(body.url, /^https:\/\/worker\/launcher\/download\?/);
  assert.doesNotMatch(body.url, /ticket-secret/);
});

test("metadata is fail closed without an authenticated identity or owner bindings", async () => {
  const request = new Request("https://worker/launcher/update/darwin/universal/0.9.1");
  assert.equal((await handleUpdateMetadata(request, env(), { identity: null }, 1_000)).status, 403);
  assert.equal((await handleUpdateMetadata(request, {}, { identity: "friend" }, 1_000)).status, 503);
});

test("download tickets stream one immutable object and expire", async () => {
  const metadata = await handleUpdateMetadata(
    new Request("https://worker/launcher/update/darwin/universal/0.9.1"), env(),
    { identity: "friend" }, 1_000,
  );
  const { url } = await metadata.json();
  const ok = await handleUpdateDownload(new Request(url), env(), 1_001);
  assert.equal(ok.status, 200);
  assert.equal(await ok.text(), "update-bytes");
  const expired = await handleUpdateDownload(new Request(url), env(), 1_000 + 16 * 60);
  assert.equal(expired.status, 403);
});

test("telemetry accepts only bounded anonymous enum fields", async () => {
  const calls = [];
  const db = {
    prepare(sql) {
      return { bind(...values) { calls.push({ sql, values }); return { run: async () => ({ success: true }) }; } };
    },
  };
  const request = new Request("https://worker/launcher/update-event", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ event: "download_verified", currentVersion: "0.9.1", targetVersion: "0.10.0", platform: "darwin", arch: "universal" }),
  });
  assert.equal((await handleUpdateEvent(request, { UPDATE_EVENTS: db }, { identity: "friend" }, 1_000)).status, 204);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].values.includes("friend"), false);

  const bad = new Request("https://worker/launcher/update-event", {
    method: "POST", body: JSON.stringify({ event: "my arbitrary log line" }),
  });
  assert.equal((await handleUpdateEvent(bad, { UPDATE_EVENTS: db }, { identity: "friend" }, 1_000)).status, 400);
});

test("scheduled cleanup deletes events older than exactly thirty days", async () => {
  const seen = [];
  const db = { prepare(sql) { return { bind(value) { seen.push({ sql, value }); return { run: async () => ({ success: true }) }; } }; } };
  await cleanupUpdateEvents({ UPDATE_EVENTS: db }, 3_000_000_000);
  assert.match(seen[0].sql, /DELETE FROM launcher_update_events/);
  assert.equal(seen[0].value, 3_000_000_000 - 30 * 24 * 60 * 60);
});
