import test from "node:test";
import assert from "node:assert/strict";

import {
  cleanupUpdateEvents,
  compareVersions,
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

// A dev build is cut from a branch whose pins are already bumped, so its version is a
// pre-release of the *next* stable: newer than what players run, older than that release.
const devManifest = {
  ...manifest,
  version: "0.11.0-dev.41",
  minimumSupportedVersion: "0.0.0",
  notes: "Dev build.",
  platforms: {
    "darwin-universal": { ...manifest.platforms["darwin-universal"], key: "releases/0.11.0-dev.41/cobblify.app.tar.gz" },
  },
};

function envWithDev(dev = JSON.stringify(devManifest)) {
  const e = env();
  e.UPDATE_BUCKET.objects.set("channels/dev.json", dev);
  return e;
}

async function versionOffered(e, path) {
  const response = await handleUpdateMetadata(new Request(`https://worker${path}`), e, { identity: "friend" }, 1_000);
  if (response.status === 204) return null;
  const text = await response.text();
  assert.equal(response.status, 200, text);
  return JSON.parse(text).version;
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

test("stable requests never see the dev channel, even when it is newer", async () => {
  const e = envWithDev();
  assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.9.1"), "0.10.0");
  assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.9.1?channel=stable"), "0.10.0");
  assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.9.1?channel=nightly"), "0.10.0");
  assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.10.0"), null);
});

test("dev requests get the newer of stable and dev", async () => {
  const e = envWithDev();
  assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.10.0?channel=dev"), "0.11.0-dev.41");
  // Already on that dev build: nothing newer.
  assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.11.0-dev.41?channel=dev"), null);
  // Stable moved past the dev build: stable wins, so opted-in launchers converge on it.
  const newerStable = envWithDev();
  newerStable.UPDATE_BUCKET.objects.set("channels/stable.json", JSON.stringify({
    ...manifest, version: "0.11.0",
    platforms: { "darwin-universal": { ...manifest.platforms["darwin-universal"], key: "releases/0.11.0/cobblify.app.tar.gz" } },
  }));
  assert.equal(await versionOffered(newerStable, "/launcher/update/darwin/universal/0.11.0-dev.41?channel=dev"), "0.11.0");
});

test("dev requests fall back to stable when the dev manifest is missing or malformed", async () => {
  assert.equal(await versionOffered(env(), "/launcher/update/darwin/universal/0.9.1?channel=dev"), "0.10.0");
  assert.equal(await versionOffered(envWithDev("{not json"), "/launcher/update/darwin/universal/0.9.1?channel=dev"), "0.10.0");
  assert.equal(await versionOffered(envWithDev(JSON.stringify({ ...devManifest, schema: 2 })), "/launcher/update/darwin/universal/0.9.1?channel=dev"), "0.10.0");
  // Dev manifest present but stable broken: dev still serves.
  const noStable = envWithDev();
  noStable.UPDATE_BUCKET.objects.delete("channels/stable.json");
  assert.equal(await versionOffered(noStable, "/launcher/update/darwin/universal/0.9.1?channel=dev"), "0.11.0-dev.41");
});

test("pre-release identifiers compare per semver, not as strings", () => {
  assert.equal(compareVersions("0.11.0-dev.10", "0.11.0-dev.9"), 1);
  assert.equal(compareVersions("0.11.0-dev.9", "0.11.0-dev.10"), -1);
  assert.equal(compareVersions("0.11.0-dev.10", "0.11.0-dev.10"), 0);
  assert.equal(compareVersions("0.11.0", "0.11.0-dev.10"), 1);
  assert.equal(compareVersions("0.11.0-dev.10", "0.11.0"), -1);
  assert.equal(compareVersions("0.11.0-dev.1", "0.10.9"), 1);
  assert.equal(compareVersions("0.11.0-alpha", "0.11.0-alpha.1"), -1);
  assert.equal(compareVersions("0.11.0-1", "0.11.0-a"), -1);
  assert.equal(compareVersions("0.11.0-dev.10", "0.11.0-dev.9.1"), 1);
  assert.equal(compareVersions("0.11.0-dev.10", "bogus"), null);
  // Exact beyond Number's safe integer range, in both operand orders.
  assert.equal(compareVersions("1.0.0-dev.9007199254740993", "1.0.0-dev.9007199254740992"), 1);
  assert.equal(compareVersions("1.0.0-dev.9007199254740992", "1.0.0-dev.9007199254740993"), -1);
  assert.equal(compareVersions("1.0.0-dev.9007199254740993", "1.0.0-dev.9007199254740993"), 0);
  assert.equal(compareVersions("99999999999999999999.0.0", "9999999999999999999.0.0"), 1);
  // §9: empty identifiers and numeric identifiers with leading zeros are not versions at all.
  assert.equal(compareVersions("0.11.0-dev.010", "0.11.0-dev.9"), null);
  assert.equal(compareVersions("0.11.0-dev..41", "0.11.0"), null);
  assert.equal(compareVersions("0.11.0-", "0.11.0"), null);
  assert.equal(compareVersions("0.11.0-dev.0", "0.11.0-dev.1"), -1);
  assert.equal(compareVersions("0.11.0-0a.1", "0.11.0"), -1);
});

test("a malformed dev manifest never displaces stable", async () => {
  for (const version of ["0.11.0-dev..41", "0.11.0-dev.041", "0.11.0-", "0.11.0-dev.41+build"]) {
    const e = envWithDev(JSON.stringify({ ...devManifest, version }));
    assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.10.0?channel=dev"), null, version);
    assert.equal(await versionOffered(e, "/launcher/update/darwin/universal/0.9.1?channel=dev"), "0.10.0", version);
  }
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

// L4: the signed release blob is passed through verbatim when present, and its absence keeps
// the manifest published before it existed serving.
const signedRelease = JSON.stringify({
  schema: 1,
  version: "0.10.0",
  minimumSupportedVersion: "0.9.0",
  platforms: { "darwin-universal": { key: "releases/0.10.0/cobblify.app.tar.gz", sha256: "a".repeat(64), sizeBytes: 12 } },
});

async function metadataFor(e, path = "/launcher/update/darwin/universal/0.9.1") {
  const response = await handleUpdateMetadata(new Request(`https://worker${path}`), e, { identity: "friend" }, 1_000);
  return { status: response.status, body: response.status === 200 ? await response.json() : await response.text() };
}

test("a manifest without the release blob still serves, and the reply carries no release fields", async () => {
  const { status, body } = await metadataFor(env());
  assert.equal(status, 200);
  assert.equal("release" in body, false);
  assert.equal("releaseSignature" in body, false);
});

test("the release blob and its signature pass through exactly as published", async () => {
  const e = env();
  e.UPDATE_BUCKET.objects.set("channels/stable.json", JSON.stringify({
    ...manifest, release: signedRelease, releaseSignature: "trusted-release-signature",
  }));
  const { status, body } = await metadataFor(e);
  assert.equal(status, 200);
  assert.equal(body.release, signedRelease);
  assert.equal(body.releaseSignature, "trusted-release-signature");
  // The fields launchers before 0.16.0 read are untouched.
  assert.equal(body.policy, manifest.policy);
  assert.equal(body.policySignature, manifest.policySignature);
  assert.equal(body.sha256, "a".repeat(64));
});

test("an incoherent release blob is an invalid manifest, not a silent pass-through", async () => {
  for (const patch of [
    { release: signedRelease },
    { release: 42, releaseSignature: "sig" },
    { release: "{not json", releaseSignature: "sig" },
    { release: JSON.stringify({ schema: 1, version: "0.9.9", platforms: {} }), releaseSignature: "sig" },
    { release: JSON.stringify({ schema: 2, version: "0.10.0", platforms: {} }), releaseSignature: "sig" },
  ]) {
    const e = env();
    e.UPDATE_BUCKET.objects.set("channels/stable.json", JSON.stringify({ ...manifest, ...patch }));
    const { status, body } = await metadataFor(e);
    assert.equal(status, 503, JSON.stringify(patch));
    assert.match(body, /invalid_release_manifest/);
  }
});
