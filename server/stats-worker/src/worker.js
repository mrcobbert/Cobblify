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
  seraphAllowed,
  seraphCapable,
  tagsForUuids as seraphTagsForUuids,
  resultFields as seraphResultFields,
  handleKeySet as seraphHandleKeySet,
} from "./seraph.js";

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|challenge-platform|attention required/i;

const NAME_RE = /^[A-Za-z0-9_]{1,16}$/;

// &prio=1 marks a request a human is waiting on: it is served first by the shared origin gate,
// which reorders work without ever raising the origin request rate.
const laneFor = (url) => (url.searchParams.get("prio") === "1" ? LANE_HIGH : LANE_LOW);

export default {
  async scheduled(event, env, ctx) {
    const result = await probeHypixelPlayer("beepor", env);
    console.log("SCHEDULED_PROBE", JSON.stringify(result));
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";

    // ONE auth derivation per request: every route consumes this object; nothing below
    // re-reads the token header or the secret.
    const auth = await authenticate(request, env);

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
      const namesParam = url.searchParams.get("names") || "";
      const names = namesParam.split(",").map((s) => decodeURIComponent(s.trim())).filter(Boolean);
      if (names.length === 0) {
        return jsonResponse({ success: false, error: "no_names" }, 400);
      }
      // Provider enrichment: authenticated identity + per-provider opt-in header + KV, plus
      // index-aligned uuids ("-" = ineligible member, never resolved). Anything off -> pure
      // legacy stream. The uuid alignment is provider-independent, so it is built once and shared.
      const uAllowed = urchinAllowed(auth, request);
      const sAllowed = seraphAllowed(auth, request);
      let urchinCtx = null;
      let seraphCtx = null;
      if (uAllowed || sAllowed) {
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
            if (sAllowed) seraphCtx = { uuidByName, unavailableOnly: !seraphCapable(env), auth };
          }
        }
      }
      // Streams NDJSON (one line per player as it resolves) — not a buffered JSON response.
      return streamBedwarsBatch(names, env, ctx, urchinCtx, seraphCtx, laneFor(url));
    }

    const bedwars = path.match(/^\/bedwars\/([^/]+)$/);
    if (bedwars) {
      if (auth.denied) return auth.denied;
      const player = decodeURIComponent(bedwars[1]);
      if (!NAME_RE.test(player)) {
        return jsonResponse({ success: false, error: "invalid_player_name", player }, 400);
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
      // Automatic-single Seraph enrichment is likewise UUID-only, gated by its own opt-in header.
      if (seraphAllowed(auth, request)) {
        const uuid = normalizeUuid(url.searchParams.get("uuid") || "");
        if (uuid) {
          if (!seraphCapable(env)) {
            body.seraphUnavailable = true;
          } else {
            try {
              const results = await seraphTagsForUuids([uuid], env, ctx, auth);
              Object.assign(body, seraphResultFields(results.get(uuid), uuid));
            } catch (_) { /* silent degradation */ }
          }
        }
      }
      return jsonResponse(body);
    }

    if (path === "/urchin/key" && request.method === "POST") {
      return handleKeySet(request, env, ctx, auth);
    }

    if (path === "/seraph/key" && request.method === "POST") {
      return seraphHandleKeySet(request, env, ctx, auth);
    }

    // Manual lookup: the only name-resolving Coral path.
    const urchin = path.match(/^\/urchin\/([^/]+)$/);
    if (urchin && request.method === "GET") {
      if (!urchinAllowed(auth, request)) {
        return jsonResponse({ success: false, error: "unauthorized" }, 403);
      }
      const player = decodeURIComponent(urchin[1]);
      if (!NAME_RE.test(player)) {
        return jsonResponse({ success: false, error: "invalid_player_name", player }, 400);
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

    // Manual Seraph lookup: UUID-only. Seraph exposes no name endpoint, so the client resolves
    // name->uuid before calling; the route otherwise mirrors the urchin manual route's gating/shape.
    const seraph = path.match(/^\/seraph\/([^/]+)$/);
    if (seraph && request.method === "GET") {
      if (!seraphAllowed(auth, request)) {
        return jsonResponse({ success: false, error: "unauthorized" }, 403);
      }
      const uuid = normalizeUuid(decodeURIComponent(seraph[1]));
      if (!uuid) {
        return jsonResponse({ success: false, error: "invalid_uuid" }, 400);
      }
      if (!seraphCapable(env)) {
        return jsonResponse({ success: true, uuid, tags: [], notFound: false, unavailable: true });
      }
      const results = await seraphTagsForUuids([uuid], env, ctx, auth);
      const r = results.get(uuid) || { state: "unavailable", tags: [] };
      return jsonResponse({
        success: true,
        uuid,
        tags: (r.tags || []).map(({ kind, subtype, reason, addedOn, verified }) => ({
          kind, ...(subtype ? { subtype } : {}), reason, addedOn, ...(verified ? { verified: true } : {}),
        })),
        notFound: r.state === "notfound",
        unavailable: r.state === "unavailable",
      });
    }

    const test = path.match(/^\/test\/([^/]+)$/);
    if (test) {
      if (auth.denied) return auth.denied;
      const player = decodeURIComponent(test[1]);
      if (!NAME_RE.test(player)) {
        return jsonResponse({ error: "invalid_player_name", player }, 400);
      }
      return jsonResponse(await probeHypixelPlayer(player, env));
    }

    return new Response("Not found. Try GET /bedwars/PlayerName", { status: 404 });
  },
};

/**
 * Gate the data endpoints behind the STATS_TOKEN secret: a comma-separated token list
 * (split on ",", trim, drop empties, dedupe preserving order). The FIRST surviving entry
 * is the OWNER. If the secret is unset the Worker stays open (back-compat, and lets a
 * friend run their own no-auth deployment) - the open result carries a null identity, so
 * every provider route stays fail-closed exactly as before.
 * Set it with: wrangler secret put STATS_TOKEN
 *
 * Returns {denied: Response} on mismatch, else {identity, isOwner} where `identity` is the
 * first 16 hex chars of SHA-256(token) - the suffix that scopes all per-user provider
 * state. Parse and hashes are memoized per isolate, keyed by the raw secret string, so a
 * redeploy with a changed list invalidates the memo.
 */
let tokenMemo = { raw: undefined, tokens: [], ids: new Map() };

function parseTokenList(raw) {
  const out = [];
  for (const part of String(raw).split(",")) {
    const t = part.trim();
    if (t && !out.includes(t)) out.push(t);
  }
  return out;
}

async function tokenIdentity(token) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token));
  return [...new Uint8Array(digest).slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function authenticate(request, env) {
  const raw = (env && env.STATS_TOKEN) || "";
  if (tokenMemo.raw !== raw) tokenMemo = { raw, tokens: parseTokenList(raw), ids: new Map() };
  if (tokenMemo.tokens.length === 0) return { identity: null, isOwner: false }; // open worker
  const got = request.headers.get("X-BedwarsQol-Token");
  const idx = got == null ? -1 : tokenMemo.tokens.indexOf(got);
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
