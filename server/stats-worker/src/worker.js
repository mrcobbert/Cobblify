/**
 * BedwarsQol stats backend — scrapes hypixel.net/player/<name> from Cloudflare edge.
 *
 * GET /bedwars/<name>          -> Bedwars JSON for one player (USER/immediate path)
 * GET /bedwars/batch?names=a,b -> Bedwars JSON for many players in one call (background lobby path)
 * GET /test/<name>             -> diagnostic egress probe
 * GET /health                  -> health check
 *
 * Caching is two-tier (per-colo caches.default + optional global Workers KV); cache-miss scrapes are
 * paced server-side with 429 backoff so the mod never has to rate-limit itself.
 */

import {
  getBedwars,
  streamBedwarsBatch,
  claimOrigin,
  noteOrigin429,
  LANE_HIGH,
  LANE_LOW,
  retiredSeraphFields,
} from "./scrape.js";
import {
  urchinAllowed,
  urchinCapable,
  normalizeUuid,
  tagsForUuids,
  tagsForName,
  resultFields,
  handleKeySet,
} from "./urchin.js";
import {
  cleanupUpdateEvents,
  handleUpdateDownload,
  handleUpdateEvent,
  handleUpdateMetadata,
} from "./update-routes.js";

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

// Actual interstitial markers only — see the note in scrape.js on why the beacon token is absent.
const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|attention required/i;

const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;

/**
 * Decode one path segment. `decodeURIComponent` raises URIError on a malformed escape (%zz, a
 * truncated UTF-8 sequence), which used to leave fetch() and become a 500; null lets the route
 * answer its own 400 instead.
 */
function decodeSegment(s) {
  try {
    return decodeURIComponent(s);
  } catch (_) {
    return null;
  }
}

// &prio=1 marks a request a human is waiting on: it is served first by the shared origin gate,
// which reorders work without ever raising the origin request rate.
const laneFor = (url) => (url.searchParams.get("prio") === "1" ? LANE_HIGH : LANE_LOW);

export default {
  async scheduled(event, env, ctx) {
    try {
      await cleanupUpdateEvents(env);
    } catch (error) {
      // Telemetry retention must never suppress the existing health probe.
      console.warn("UPDATE_EVENT_CLEANUP_FAILED", error instanceof Error ? error.message : "unknown");
    }
    const result = await probeHypixelPlayer("beepor", env);
    console.log("SCHEDULED_PROBE", JSON.stringify(result));
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    // ONE auth derivation per request: every route consumes this object; nothing below
    // re-reads the token header or the secret.
    const auth = await authenticate(request, env);

    if (path === "/launcher/download" && request.method === "GET") {
      return handleUpdateDownload(request, env);
    }
    if (path === "/launcher/update-event" && request.method === "POST") {
      if (auth.denied) return auth.denied;
      return handleUpdateEvent(request, env, auth);
    }
    if (/^\/launcher\/update\//.test(path) && request.method === "GET") {
      if (auth.denied) return auth.denied;
      return handleUpdateMetadata(request, env, auth);
    }

    if (path === "/" || path === "/health") {
      if (path === "/health") {
        if (auth.denied) return auth.denied;
        const p = await probeHypixelPlayer("beepor", env);
        p.workerColo = (request.cf && request.cf.colo) || null; // where the Worker itself executes
        return jsonResponse(p);
      }
      return new Response(usageText(), { headers: { "content-type": "text/plain; charset=utf-8" } });
    }

    // Batch must be matched before the single /bedwars/<name> route ("batch" is a valid name pattern).
    if (path === "/bedwars/batch") {
      if (auth.denied) return auth.denied;
      // URLSearchParams never throws on a malformed escape (it substitutes U+FFFD), so the RAW
      // query is strict-checked first - otherwise a bad escape reaches the name filter as garbage.
      try {
        decodeURIComponent(url.search);
      } catch (_) {
        return jsonResponse({ success: false, error: "malformed_query" }, 400);
      }
      const namesParam = url.searchParams.get("names") || "";
      // searchParams.get already decoded once. A second decode threw URIError on a name that
      // legitimately encodes a "%" (names=%25zz -> "%zz", dropped below as invalid, never a 400).
      const names = namesParam.split(",").map((s) => s.trim()).filter(Boolean);
      if (names.length === 0) {
        return jsonResponse({ success: false, error: "no_names" }, 400);
      }
      // Provider enrichment: authenticated identity + per-provider opt-in header + KV, plus
      // index-aligned uuids ("-" = ineligible member, never resolved). Anything off -> pure
      // legacy stream. The uuid alignment is provider-independent, so it is built once and shared.
      const uAllowed = urchinAllowed(auth, request);
      const sOptIn = retiredSeraphOptIn(auth, request);
      let urchinCtx = null;
      let retiredSeraph = null;
      if (uAllowed || sOptIn) {
        const uuidsParam = url.searchParams.get("uuids") || "";
        const rawUuids = uuidsParam ? uuidsParam.split(",").map((s) => s.trim()) : [];
        if (rawUuids.length === names.length) {
          const uuidByName = new Map();
          const conflicted = new Set();
          for (let i = 0; i < names.length; i++) {
            const u = rawUuids[i] === "-" ? null : normalizeUuid(rawUuids[i]);
            if (!u) continue;
            const k = names[i].toLowerCase();
            // Case-varied duplicate names with DIFFERENT uuids cannot be aligned through the
            // case-insensitive stream dedupe - fail closed: neither uuid resolves via this
            // batch (the client's bounded single path picks each up unambiguously).
            if (uuidByName.has(k) && uuidByName.get(k) !== u) conflicted.add(k);
            else uuidByName.set(k, u);
          }
          for (const k of conflicted) uuidByName.delete(k);
          // Without STATS_KV a provider makes zero upstream calls but still tells the owner
          // client "resolved unavailable" so it does not misdiagnose authentication.
          if (uuidByName.size > 0) {
            if (uAllowed) urchinCtx = { uuidByName, unavailableOnly: !urchinCapable(env), auth };
            if (sOptIn) retiredSeraph = uuidByName;
          }
        }
      }
      // Streams NDJSON (one line per player as it resolves) — not a buffered JSON response.
      return streamBedwarsBatch(names, env, ctx, urchinCtx, retiredSeraph, laneFor(url));
    }

    const bedwars = path.match(/^\/bedwars\/([^/]+)$/);
    if (bedwars) {
      if (auth.denied) return auth.denied;
      const player = decodeSegment(bedwars[1]);
      if (player === null || !NAME_RE.test(player)) {
        return jsonResponse({ success: false, error: "invalid_player_name", player: player ?? bedwars[1] }, 400);
      }
      const fresh = url.searchParams.get("fresh") === "1";
      const body = await getBedwars(player, env, ctx, fresh, laneFor(url));
      // Automatic-single Urchin enrichment is UUID-only: the eligible client sends
      // ?uuid=<canonical>; missing/invalid uuid -> no Coral lookup, no resolution metadata.
      if (urchinAllowed(auth, request)) {
        const uuid = normalizeUuid(url.searchParams.get("uuid") || "");
        if (uuid) {
          if (!urchinCapable(env)) {
            body.urchinUnavailable = true;
          } else {
            try {
              const results = await tagsForUuids([uuid], env, ctx, auth);
              Object.assign(body, resultFields(results.get(uuid), uuid));
            } catch (_) { /* silent degradation */ }
          }
        }
      }
      // Seraph is retired: an old client that still opts in gets its final answer, no upstream call.
      if (retiredSeraphOptIn(auth, request)) {
        const uuid = normalizeUuid(url.searchParams.get("uuid") || "");
        if (uuid) Object.assign(body, retiredSeraphFields(uuid));
      }
      return jsonResponse(body);
    }

    if (path === "/urchin/key" && request.method === "POST") {
      return handleKeySet(request, env, ctx, auth);
    }

    if (path === "/seraph/key" && request.method === "POST") {
      return retiredSeraphKey(request, env, auth);
    }

    // Manual lookup: the only name-resolving Coral path.
    const urchin = path.match(/^\/urchin\/([^/]+)$/);
    if (urchin && request.method === "GET") {
      if (!urchinAllowed(auth, request)) {
        return jsonResponse({ success: false, error: "unauthorized" }, 403);
      }
      const player = decodeSegment(urchin[1]);
      if (player === null || !NAME_RE.test(player)) {
        return jsonResponse({ success: false, error: "invalid_player_name", player: player ?? urchin[1] }, 400);
      }
      if (!urchinCapable(env)) {
        return jsonResponse({
          success: true, player, uuid: null, tags: [], stale: false, notFound: false, unavailable: true,
        });
      }
      const r = await tagsForName(player, env, ctx, auth);
      return jsonResponse({
        success: true,
        player,
        uuid: r.uuid || null,
        tags: (r.tags || []).map(({ type, reason, addedOn, expiresAt }) => ({
          type, reason, addedOn, ...(expiresAt != null ? { expiresAtMs: expiresAt } : {}),
        })),
        stale: r.stale === true,
        notFound: r.state === "notfound",
        unavailable: r.state === "unavailable",
      });
    }

    // Retired manual Seraph lookup: same gate and validation as before, a final "unavailable"
    // answer, no upstream call.
    const seraph = path.match(/^\/seraph\/([^/]+)$/);
    if (seraph && request.method === "GET") {
      if (!retiredSeraphOptIn(auth, request)) {
        return jsonResponse({ success: false, error: "unauthorized" }, 403);
      }
      const rawUuid = decodeSegment(seraph[1]);
      const uuid = rawUuid === null ? null : normalizeUuid(rawUuid);
      if (!uuid) {
        return jsonResponse({ success: false, error: "invalid_uuid" }, 400);
      }
      return jsonResponse({ success: true, uuid, tags: [], notFound: false, unavailable: true });
    }

    const test = path.match(/^\/test\/([^/]+)$/);
    if (test) {
      if (auth.denied) return auth.denied;
      const player = decodeSegment(test[1]);
      if (player === null || !NAME_RE.test(player)) {
        return jsonResponse({ error: "invalid_player_name", player: player ?? test[1] }, 400);
      }
      return jsonResponse(await probeHypixelPlayer(player, env));
    }

    return new Response("Not found. Try GET /bedwars/PlayerName", { status: 404 });
  },
};

// ---- Seraph (retired) ---------------------------------------------------------------------
// Seraph's public API shut down in October 2026 (502 on every route); nothing here calls it.
// Clients up to 0.18 still send `X-BWQOL-Seraph: 1` for Seraph-eligible players and may call
// /seraph/<uuid> and /seraph/key. They treat `seraphUnavailable` (with the `seraphUuid` the batch
// merge keys on) as a concluded lookup and never ask again for that player, whereas a missing field
// keeps the player pending and re-asked every 60 s. So every opted-in lookup gets that final answer.

/** The old Seraph gate, unchanged: an authenticated identity that sent the opt-in header. */
function retiredSeraphOptIn(auth, request) {
  return Boolean(auth && auth.identity && request.headers.get("X-BWQOL-Seraph") === "1");
}

/**
 * POST /seraph/key. A clear deletes whatever this identity once stored and reports the truth; a set
 * is refused (410) and stores nothing. Never echoes the key.
 */
async function retiredSeraphKey(request, env, auth) {
  if (!(auth && auth.identity)) return jsonResponse({ success: false, error: "unauthorized" }, 403);
  let body;
  try { body = await request.json(); } catch (_) {
    return jsonResponse({ success: false, error: "bad_request" }, 400);
  }
  if (!body || typeof body !== "object" || !Object.prototype.hasOwnProperty.call(body, "key")) {
    return jsonResponse({ success: false, error: "bad_request" }, 400);
  }
  if (body.key !== null) return jsonResponse({ success: false, error: "seraph_retired" }, 410);
  if (env && env.STATS_KV) {
    try {
      for (const k of ["key", "backoff", "disabled"]) {
        await env.STATS_KV.delete(`seraph:cfg:${k}:${auth.identity}`);
      }
    } catch (_) {
      return jsonResponse({ success: false, error: "kv_write_failed" }, 503);
    }
  }
  return jsonResponse({ success: true });
}

/**
 * Gate the data endpoints behind the STATS_TOKEN secret: a comma-separated token list
 * (split on ",", trim, drop empties, dedupe preserving order). Every surviving entry must
 * match TOKEN_RE (^[A-Za-z0-9_-]{16,64}$) - entries that don't are DROPPED and can never
 * authenticate anything, so an accidental weak or malformed deployment secret never becomes
 * a live credential. The FIRST surviving VALID entry is the OWNER.
 *
 * Fail-closed rule: an unset secret (or one containing only empty segments) leaves the
 * Worker open (back-compat, and lets a friend run their own no-auth deployment) - the open
 * result carries a null identity, so every provider route stays fail-closed exactly as
 * before. But a secret that HAS non-empty entries yet yields zero valid ones means auth was
 * intended and misconfigured: every authenticated route is DENIED, never fallen open.
 * Set it with: wrangler secret put STATS_TOKEN
 *
 * Returns {denied: Response} on mismatch, else {identity, isOwner} where `identity` is the
 * first 16 hex chars of SHA-256(token) - the suffix that scopes all per-user provider
 * state. Parse and hashes are memoized per isolate, keyed by the raw secret string, so a
 * redeploy with a changed list invalidates the memo.
 */
const TOKEN_RE = /^[A-Za-z0-9_-]{16,64}$/;

let tokenMemo = { raw: undefined, tokens: [], invalid: 0, ids: new Map(), hmacKeys: new Map() };

function parseTokenList(raw) {
  const out = [];
  let invalid = 0;
  for (const part of String(raw).split(",")) {
    const t = part.trim();
    if (!t) continue; // empty segments are ignored, not counted as invalid
    if (!TOKEN_RE.test(t)) { invalid++; continue; } // malformed: dropped, never a credential
    if (!out.includes(t)) out.push(t);
  }
  return { tokens: out, invalid };
}

async function tokenIdentity(token) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return [...new Uint8Array(digest).slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const AUTH_PROOF = new TextEncoder().encode("cobblify-auth-v1");

async function tokenHmacKey(token) {
  let key = tokenMemo.hmacKeys.get(token);
  if (!key) {
    key = crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(token),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign", "verify"],
    );
    tokenMemo.hmacKeys.set(token, key);
  }
  return key;
}

export async function authenticate(request, env) {
  const raw = (env && env.STATS_TOKEN) || "";
  if (tokenMemo.raw !== raw) {
    tokenMemo = { raw, ...parseTokenList(raw), ids: new Map(), hmacKeys: new Map() };
  }
  if (tokenMemo.tokens.length === 0) {
    if (tokenMemo.invalid > 0) {
      // Secret set but every entry malformed: auth was intended - deny, never fall open.
      return { denied: jsonResponse({ success: false, state: "ERROR", error: "unauthorized" }, 401) };
    }
    return { identity: null, isOwner: false }; // open worker (secret unset/empty)
  }
  const got = request.headers.get("X-BedwarsQol-Token");
  let idx = -1;
  if (got != null && TOKEN_RE.test(got)) {
    const suppliedKey = await crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(got),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    );
    const suppliedProof = await crypto.subtle.sign("HMAC", suppliedKey, AUTH_PROOF);
    // Check every configured token. WebCrypto performs the MAC comparison;
    // never expose a prefix-sensitive JavaScript string comparison.
    for (let i = 0; i < tokenMemo.tokens.length; i++) {
      const valid = await crypto.subtle.verify(
        "HMAC",
        await tokenHmacKey(tokenMemo.tokens[i]),
        suppliedProof,
        AUTH_PROOF,
      );
      if (valid) idx = i;
    }
  }
  if (idx < 0) {
    return { denied: jsonResponse({ success: false, state: "ERROR", error: "unauthorized" }, 401) };
  }
  let id = tokenMemo.ids.get(got);
  if (!id) {
    id = tokenIdentity(got); // memoized promise: one hash per token per isolate lifetime
    tokenMemo.ids.set(got, id);
  }
  return { identity: await id, isOwner: idx === 0 };
}

/** Diagnostic egress probe (full read, no cache) — used by /test, /health and the cron. */
async function probeHypixelPlayer(player, env) {
  const base = (env && env.HYPIXEL_BASE) || "https://hypixel.net";
  const target = `${base}/player/${encodeURIComponent(player)}`;
  // The probe fetches the same full page as a scrape, so it must claim an origin slot too. HIGH:
  // /health and /test are interactive. Claimed before `started` so elapsedMs stays fetch-only.
  await claimOrigin(LANE_HIGH);
  const started = Date.now();

  let response;
  let fetchError = null;
  try {
    response = await fetch(target, {
      method: "GET",
      headers: {
        "User-Agent": BROWSER_UA,
        Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
      },
      redirect: "follow",
    });
  } catch (e) {
    fetchError = String(e && e.message ? e.message : e);
  }

  const elapsedMs = Date.now() - started;

  if (fetchError) {
    return {
      ok: false,
      verdict: "FETCH_FAILED",
      player,
      target,
      fetchError,
      elapsedMs,
      testedAt: new Date().toISOString(),
      from: "cloudflare-worker",
    };
  }

  // Feed the shared gate, exactly as the scrape paths do, so a probe 429 throttles everything.
  if (response.status === 429) noteOrigin429(1);

  const body = await response.text();
  const titleMatch = body.match(/<title[^>]*>([^<]+)<\/title>/i);
  const title = titleMatch ? titleMatch[1].trim() : null;

  const hasBedwars = body.includes("stats-content-bedwars");
  const looksLikeChallenge = body.length < 50_000 && CHALLENGE_RE.test(body);
  const cfMitigated = response.headers.get("cf-mitigated");
  const cfRay = response.headers.get("cf-ray");
  const server = response.headers.get("server");

  let verdict;
  if (response.status === 200 && hasBedwars) verdict = "PASS";
  else if (response.status === 429) verdict = "RATE_LIMITED";
  else if (response.status === 403 || looksLikeChallenge) verdict = "BLOCKED";
  else if (response.status === 200 && !hasBedwars) verdict = "UNEXPECTED_HTML";
  else verdict = "FAIL";

  return {
    ok: verdict === "PASS",
    verdict,
    player,
    target,
    httpStatus: response.status,
    bodyBytes: body.length,
    title,
    hasBedwarsSection: hasBedwars,
    looksLikeCloudflareBlock: looksLikeChallenge,
    cfMitigated,
    cfRay,
    server,
    elapsedMs,
    testedAt: new Date().toISOString(),
    from: "cloudflare-worker",
    hint:
      verdict === "PASS"
        ? "Worker egress can scrape this page today."
        : verdict === "BLOCKED"
          ? "Worker egress is blocked."
          : verdict === "RATE_LIMITED"
            ? "Origin rate-limited this egress IP; the shared gate has been throttled."
            : "Unexpected response; inspect title/bodyBytes.",
  };
}

function jsonResponse(obj, status = 200) {
  return new Response(JSON.stringify(obj, null, 2), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function usageText() {
  return [
    "BedwarsQol Hypixel stats Worker",
    "",
    "  GET /bedwars/<player>        -> stats JSON for one player",
    "  GET /bedwars/batch?names=a,b -> stats JSON for many players in one call",
    "  GET /test/<player>           -> diagnostic egress probe",
    "  GET /health                  -> health check",
    "",
    "  &prio=1 on either /bedwars route -> served first by the origin pacer",
    "          (reorders work only; the origin request rate is unchanged)",
  ].join("\n");
}
