// Every launcher reads stable. A launcher whose "Test dev builds" preference is on asks for
// `?channel=dev` and receives whichever of stable/dev is the newer version, so opting out (or
// forgetting to) converges on stable as soon as stable moves past the dev build.
const STABLE_CHANNEL_KEY = "channels/stable.json";
const DEV_CHANNEL_KEY = "channels/dev.json";
const MAX_MANIFEST_BYTES = 64 * 1024;
const MAX_EVENT_BYTES = 2 * 1024;
const TICKET_SECONDS = 15 * 60;
const THIRTY_DAYS_SECONDS = 30 * 24 * 60 * 60;
const PLATFORM_RE = /^(darwin|windows)$/;
// Tauri reports the running CPU architecture even when the macOS artifact is
// universal, so stable manifests publish both darwin aliases to one R2 key.
const ARCH_RE = /^(universal|x86_64|aarch64)$/;
// SemVer 2.0.0 §9: every pre-release identifier is non-empty and a numeric one has no
// leading zeros, so nothing a comparator would misorder can reach the channel choice.
const VERSION_RE = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?$/;
const EVENT_RE = /^(check_ok|check_failed|download_started|download_paused|download_verified|install_started|post_update_started)$/;
const SHA256_RE = /^[a-f0-9]{64}$/;

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function parseVersion(value) {
  const match = VERSION_RE.exec(value || "");
  if (!match) return null;
  return { numbers: [match[1], match[2], match[3]], prerelease: match[4] || null };
}

// Numeric identifiers compare by length then digit-wise (no leading zeros in semver), which
// stays exact for identifiers beyond Number's safe range.
function compareNumeric(a, b) {
  if (a.length !== b.length) return a.length < b.length ? -1 : 1;
  return a === b ? 0 : a < b ? -1 : 1;
}

// semver.org §11.4: dot-separated identifiers, numeric ones compare as numbers and rank below
// alphanumeric ones, and a longer list wins when every shared identifier is equal. This is what
// makes `dev.10` newer than `dev.9`.
function comparePrerelease(a, b) {
  const left = a.split(".");
  const right = b.split(".");
  const shared = Math.min(left.length, right.length);
  for (let i = 0; i < shared; i++) {
    const l = left[i];
    const r = right[i];
    if (l === r) continue;
    const ln = /^\d+$/.test(l);
    const rn = /^\d+$/.test(r);
    if (ln && rn) {
      const c = compareNumeric(l, r);
      if (c !== 0) return c;
      continue;
    }
    if (ln !== rn) return ln ? -1 : 1;
    return l < r ? -1 : 1;
  }
  if (left.length === right.length) return 0;
  return left.length < right.length ? -1 : 1;
}

export function compareVersions(a, b) {
  const left = parseVersion(a);
  const right = parseVersion(b);
  if (!left || !right) return null;
  for (let i = 0; i < 3; i++) {
    const c = compareNumeric(left.numbers[i], right.numbers[i]);
    if (c !== 0) return c;
  }
  if (left.prerelease === right.prerelease) return 0;
  if (left.prerelease == null) return 1;
  if (right.prerelease == null) return -1;
  return comparePrerelease(left.prerelease, right.prerelease);
}

function bytesToBase64Url(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

async function ticketSignature(secret, key, expires) {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
  const payload = new TextEncoder().encode(`${key}\n${expires}`);
  return { cryptoKey, payload, signature: new Uint8Array(await crypto.subtle.sign("HMAC", cryptoKey, payload)) };
}

function base64UrlToBytes(value) {
  if (!/^[A-Za-z0-9_-]{43}$/.test(value || "")) return null;
  const padded = value.replaceAll("-", "+").replaceAll("_", "/") + "=";
  try {
    return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
  } catch (_) {
    return null;
  }
}

async function readManifest(bucket, key) {
  const object = await bucket.get(key);
  if (!object || object.size > MAX_MANIFEST_BYTES) return null;
  let manifest;
  try {
    manifest = JSON.parse(await object.text());
  } catch (_) {
    return null;
  }
  if (manifest?.schema !== 1 || !parseVersion(manifest.version)) return null;
  if (!parseVersion(manifest.minimumSupportedVersion)) return null;
  if (typeof manifest.policy !== "string" || typeof manifest.policySignature !== "string") return null;
  if (!manifest.platforms || typeof manifest.platforms !== "object") return null;
  // The signed release blob (launcher 0.16.0+). Optional here so the manifest published before
  // it existed keeps serving; when present it must be a coherent pair, or the launcher would
  // reject it anyway and this route would be lying about what it offers.
  if (manifest.release !== undefined || manifest.releaseSignature !== undefined) {
    if (typeof manifest.release !== "string" || typeof manifest.releaseSignature !== "string") return null;
    let release;
    try {
      release = JSON.parse(manifest.release);
    } catch (_) {
      return null;
    }
    if (release?.schema !== 1 || release.version !== manifest.version) return null;
  }
  return manifest;
}

// Stable is always the baseline. A missing or malformed dev manifest never breaks a dev
// request; it just means "nothing newer than stable".
async function readChannelManifest(bucket, channel) {
  const stable = await readManifest(bucket, STABLE_CHANNEL_KEY);
  if (channel !== "dev") return stable;
  const dev = await readManifest(bucket, DEV_CHANNEL_KEY);
  if (!dev) return stable;
  if (!stable) return dev;
  return compareVersions(dev.version, stable.version) > 0 ? dev : stable;
}

export async function handleUpdateMetadata(request, env, auth, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!auth?.identity) return json({ error: "unauthorized" }, 403);
  if (!env.UPDATE_BUCKET || !env.UPDATE_URL_SECRET) return json({ error: "updates_unavailable" }, 503);
  const url = new URL(request.url);
  const match = url.pathname.match(/^\/launcher\/update\/([^/]+)\/([^/]+)\/([^/]+)$/);
  if (!match) return json({ error: "invalid_update_path" }, 400);
  const [, target, arch, currentVersion] = match;
  if (!PLATFORM_RE.test(target) || !ARCH_RE.test(arch) || !parseVersion(currentVersion)) {
    return json({ error: "invalid_update_target" }, 400);
  }
  const manifest = await readChannelManifest(env.UPDATE_BUCKET, url.searchParams.get("channel"));
  if (!manifest) return json({ error: "invalid_release_manifest" }, 503);
  const comparison = compareVersions(currentVersion, manifest.version);
  if (comparison == null) return json({ error: "invalid_version" }, 400);
  if (comparison >= 0) return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
  const platform = manifest.platforms[`${target}-${arch}`];
  if (!platform || typeof platform.key !== "string" || !platform.key.startsWith(`releases/${manifest.version}/`)) {
    return json({ error: "unsupported_update_target" }, 404);
  }
  if (typeof platform.signature !== "string" || !SHA256_RE.test(platform.sha256) || !Number.isSafeInteger(platform.sizeBytes)) {
    return json({ error: "invalid_release_manifest" }, 503);
  }
  const expires = nowSeconds + TICKET_SECONDS;
  const signed = await ticketSignature(env.UPDATE_URL_SECRET, platform.key, expires);
  const download = new URL("/launcher/download", url.origin);
  download.searchParams.set("key", platform.key);
  download.searchParams.set("expires", String(expires));
  download.searchParams.set("signature", bytesToBase64Url(signed.signature));
  return json({
    version: manifest.version,
    pub_date: manifest.pubDate,
    notes: manifest.notes,
    url: download.toString(),
    signature: platform.signature,
    sizeBytes: platform.sizeBytes,
    sha256: platform.sha256,
    releaseNotesUrl: manifest.releaseNotesUrl,
    minimumSupportedVersion: manifest.minimumSupportedVersion,
    policy: manifest.policy,
    policySignature: manifest.policySignature,
    // Passed through untouched when the manifest carries them: the launcher verifies the
    // signature over the exact string.
    ...(manifest.release !== undefined ? { release: manifest.release, releaseSignature: manifest.releaseSignature } : {}),
  });
}

export async function handleUpdateDownload(request, env, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!env.UPDATE_BUCKET || !env.UPDATE_URL_SECRET) return new Response("Unavailable", { status: 503 });
  const url = new URL(request.url);
  const key = url.searchParams.get("key") || "";
  const expires = Number(url.searchParams.get("expires"));
  const supplied = base64UrlToBytes(url.searchParams.get("signature"));
  if (!key.startsWith("releases/") || !Number.isSafeInteger(expires) || expires < nowSeconds || expires > nowSeconds + TICKET_SECONDS || !supplied) {
    return new Response("Forbidden", { status: 403 });
  }
  const expected = await ticketSignature(env.UPDATE_URL_SECRET, key, expires);
  const valid = await crypto.subtle.verify("HMAC", expected.cryptoKey, supplied, expected.payload);
  if (!valid) return new Response("Forbidden", { status: 403 });
  const rangeRequested = request.headers.has("range");
  const object = await env.UPDATE_BUCKET.get(key, rangeRequested ? { range: request.headers } : undefined);
  if (!object || !("body" in object)) return new Response("Not found", { status: 404 });
  const headers = new Headers({
    "accept-ranges": "bytes",
    "cache-control": "private, no-store",
    "content-length": String(object.range?.length ?? object.size),
    etag: object.httpEtag,
  });
  object.writeHttpMetadata(headers);
  if (rangeRequested && object.range && "offset" in object.range && "length" in object.range) {
    headers.set("content-range", `bytes ${object.range.offset}-${object.range.offset + object.range.length - 1}/${object.size}`);
  }
  return new Response(object.body, { status: rangeRequested ? 206 : 200, headers });
}

async function readBoundedJson(request) {
  const declared = Number(request.headers.get("content-length"));
  if (Number.isFinite(declared) && declared > MAX_EVENT_BYTES) throw new Error("too_large");
  const reader = request.body?.getReader();
  if (!reader) throw new Error("missing_body");
  const chunks = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_EVENT_BYTES) {
      await reader.cancel();
      throw new Error("too_large");
    }
    chunks.push(value);
  }
  const joined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { joined.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder().decode(joined));
}

export async function handleUpdateEvent(request, env, auth, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!auth?.identity) return json({ error: "unauthorized" }, 403);
  if (!env.UPDATE_EVENTS) return new Response(null, { status: 204 });
  let event;
  try { event = await readBoundedJson(request); } catch (_) { return json({ error: "invalid_event" }, 400); }
  if (!EVENT_RE.test(event?.event || "") || !parseVersion(event.currentVersion || "") ||
      (event.targetVersion != null && !parseVersion(event.targetVersion)) ||
      !PLATFORM_RE.test(event.platform || "") || !ARCH_RE.test(event.arch || "")) {
    return json({ error: "invalid_event" }, 400);
  }
  await env.UPDATE_EVENTS.prepare(
    "INSERT INTO launcher_update_events (occurred_at, event, current_version, target_version, platform, arch) VALUES (?, ?, ?, ?, ?, ?)",
  ).bind(nowSeconds, event.event, event.currentVersion, event.targetVersion || null, event.platform, event.arch).run();
  return new Response(null, { status: 204 });
}

export async function cleanupUpdateEvents(env, nowSeconds = Math.floor(Date.now() / 1000)) {
  if (!env.UPDATE_EVENTS) return;
  await env.UPDATE_EVENTS.prepare("DELETE FROM launcher_update_events WHERE occurred_at < ?")
    .bind(nowSeconds - THIRTY_DAYS_SECONDS).run();
}
