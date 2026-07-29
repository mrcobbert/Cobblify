/** Parse account-header fields (display name, rank) from hypixel.net/player HTML. */

export function parseProfile(html) {
  return {
    displayName: parseDisplayName(html),
    rank: parseRank(html),
  };
}

/**
 * Player display name with canonical casing — the leading text node inside
 * `<div class="playerwrapper">beepor <span class="rank-badge ...`.
 */
function parseDisplayName(html) {
  const m = html.match(/class="playerwrapper">\s*([A-Za-z0-9_]{1,16})/);
  return m ? m[1] : null;
}

/**
 * Rank code from the first `rank-badge rank-badge-<code>` inside `.playerwrapper`,
 * e.g. `mvp_plus`, `vip`, `youtuber`. The guild tag is a separate `guild-member-label`
 * anchor whose badge code is a color word — strip it before matching so it isn't
 * mistaken for the rank. Default (rankless) players have no rank badge → null.
 */
function parseRank(html) {
  const wrapper = html.match(/class="playerwrapper">([\s\S]*?)<\/h1>/);
  if (!wrapper) return null;
  const inner = wrapper[1].replace(/<a class="guild-member-label"[\s\S]*?<\/a>/g, "");
  const m = inner.match(/rank-badge rank-badge-([a-z0-9_+]+)"/);
  return m ? m[1] : null;
}
