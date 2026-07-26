/**
 * Bedwars star (level) scraper.
 *
 * The hypixel.net/player STATS page shows the Bedwars block but its "Level" field is a long-standing
 * forum-addon bug (always renders 0), and the page carries no Bedwars EXP to compute it from. The
 * player's ACHIEVEMENTS page on the same site does carry the real star: the tiered achievement
 * "Road to Prestige" (data-description="Reach %%value%% Bed Wars Level") exposes the player's current
 * level verbatim as `data-progress` — exact, uncapped (shows e.g. 1635 even though tiers stop at 100),
 * and present for every player. Same site, anonymous, no API — identical trust/risk to the stats scrape.
 *
 * Cost: it's a SECOND page per player. The star moves slowly, so it's cached for hours
 * ({@link STAR_TTL_SEC}); callers gate the scrape and run it behind the counter path so it never
 * slows the fast stats stream.
 */

import { writeStar, readBlocked } from "./cache.js";

const BROWSER_UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

const CHALLENGE_RE =
  /just a moment|cf-challenge|turnstile|challenge-platform|attention required/i;

// "Road to Prestige" sits ~10% into the ~600 KB achievements page (stable ~62 KB across players).
const NEEDLE = "Road to Prestige";
const STAR_CUSHION = 2_000; // bytes past the needle — comfortably covers data-progress on the <li>
const MAX_PREFIX_BYTES = 95_000; // hard stop; also fully contains any <50 KB challenge page

/** Read just enough of the achievements page to cover the first "Road to Prestige" row, then abort. */
async function readAchPrefix(response) {
  if (!response.body) return await response.text();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let html = "";
  let bytes = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      bytes += value.byteLength;
      html += decoder.decode(value, { stream: true });
      const idx = html.indexOf(NEEDLE);
      if ((idx >= 0 && html.length - idx >= STAR_CUSHION) || bytes >= MAX_PREFIX_BYTES) break;
    }
  } finally {
    try { await reader.cancel(); } catch (_) { /* ignore */ }
  }
  return html;
}

/**
 * Parse the Bedwars star from achievements HTML. The "Road to Prestige" tiered achievement carries
 * the current level in data-progress; every tier row repeats the same value, so the first match wins.
 * Returns an int, or null when the row is absent (markup change / challenge page / truncated read).
 */
export function parseStar(html) {
  const m = html.match(/data-name="Road to Prestige"[^>]*?data-progress="(\d+)"/);
  if (!m) return null;
  const n = parseInt(m[1], 10);
  return Number.isFinite(n) ? n : null;
}

/** Fetch + stream-read a player's achievements page. Returns { ok, html } or { ok:false, retry429? }. */
export async function scrapeStarHtml(player, env) {
  const base = (env && env.HYPIXEL_BASE) || "https://hypixel.net";
  const target = `${base}/player/${encodeURIComponent(player)}/achievements`;
  let response;
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
  } catch (_) {
    return { ok: false };
  }

  if (response.status === 429) {
    try { await response.body?.cancel(); } catch (_) {}
    return { ok: false, retry429: true };
  }

  let html;
  try { html = await readAchPrefix(response); }
  catch (_) { return { ok: false }; }

  if (response.status < 200 || response.status >= 300) return { ok: false };
  if (html.length < 50_000 && CHALLENGE_RE.test(html)) return { ok: false };
  return { ok: true, html };
}

/**
 * Scrape one star and cache it. Shaped for {@code scrapePool}: returns { retry429:true } on a 429 so
 * the caller's pool backs off and retries, otherwise { level } (level may be null on a miss/parse fail).
 * The caller is responsible for pacing (this does not gate itself).
 */
export async function scrapeStarForPool(player, env, ctx) {
  // Circuit breaker: while the origin is blocked the achievements page is doomed too.
  if (await readBlocked(env)) return { level: null };
  const r = await scrapeStarHtml(player, env);
  if (r.retry429) return { retry429: true };
  if (!r.ok) return { level: null };
  const level = parseStar(r.html);
  if (level != null) writeStar(player, level, env, ctx);
  return { level };
}
