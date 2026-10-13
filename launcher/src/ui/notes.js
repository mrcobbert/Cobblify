/**
 * Release notes shown in What's New and the history (0.18 decision 15, sourced from the bundled
 * release-notes.json: user decision 2026-10-06). Newest first: { version, channel, date, sections: [[heading,
 * [items]]] }.
 */
const MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];

export function fmtDate(iso, short = false) {
  const [y, m, d] = String(iso).split("-").map(Number);
  const mo = MONTHS[m - 1];
  if (!y || !mo || !d) return "";
  return `${d} ${short ? mo.slice(0, 3) : mo} ${y}`;
}

/** "0.18.0-next.3" -> "0.18.0" */
export const baseVersion = (v) => String(v ?? "").split(/[-+]/)[0];

/** What kind of build a version is: "stable", or its prerelease word ("dev", "next"). */
export function channelOf(v) {
  const m = /^[^-+]+-([a-z]+)/i.exec(String(v ?? ""));
  return m ? m[1].toLowerCase() : "stable";
}

/** The note for a running version: its own entry, else its release's. */
export function noteFor(notes, version) {
  return notes.find((n) => n.version === version) ?? notes.find((n) => n.version === baseVersion(version)) ?? null;
}

/** What's New's list: every item of the first section, then the next section's first two. */
export function whatsNewItems(note) {
  if (!note) return [];
  const [first, second] = note.sections ?? [];
  return [...(first?.[1] ?? []), ...(second?.[1] ?? []).slice(0, 2)];
}
