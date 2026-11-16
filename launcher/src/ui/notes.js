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

/** "0.19.0-dev.93" -> { base: "0.19.0", word: "dev", n: 93 }; null for a release or any other shape. */
function build(v) {
  const m = /^(\d+\.\d+\.\d+)-([a-z]+)\.(\d+)$/i.exec(String(v ?? ""));
  return m ? { base: m[1], word: m[2].toLowerCase(), n: Number(m[3]) } : null;
}

/**
 * What's New's notes for the first open of `version` after `previous` (the highest version opened before, or null),
 * newest first. A dev or next build that follows a build of its own line gets that line's notes after `previous`, up
 * to itself, and none when there are none: the release's note is never shown again on every build of the line.
 * Every other update (a release, the first build of a line, no previous) reads noteFor's note.
 */
export function whatsNewNotes(notes, version, previous) {
  const now = build(version), was = build(previous);
  if (now && was && was.base === now.base && was.word === now.word) {
    const line = (n) => {
      const b = build(n.version);
      return b && b.base === now.base && b.word === now.word ? b.n : null;
    };
    return notes.filter((n) => line(n) != null && line(n) > was.n && line(n) <= now.n).sort((a, b) => line(b) - line(a));
  }
  const n = noteFor(notes, version);
  return n ? [n] : [];
}

/** What's New's list: every item of the first section, then the next section's first two. */
export function whatsNewItems(note) {
  if (!note) return [];
  const [first, second] = note.sections ?? [];
  return [...(first?.[1] ?? []), ...(second?.[1] ?? []).slice(0, 2)];
}
