#!/usr/bin/env node
/**
 * SemVer 2.0.0 ordering (§11) for release tooling. Numeric pre-release identifiers compare
 * by length then digit-wise, so they stay exact beyond Number's safe range; numeric ranks
 * below alphanumeric; a longer identifier list wins when the shared prefix is equal.
 *
 * CLI: `node semver-compare.mjs A B` prints -1, 0 or 1 and exits 0; exits 2 on a bad version.
 */
const VERSION_RE = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$/;

function parse(value) {
  const match = VERSION_RE.exec(value || "");
  if (!match) return null;
  return { core: [match[1], match[2], match[3]], prerelease: match[4] ?? null };
}

function compareNumeric(a, b) {
  const l = a.replace(/^0+(?=\d)/, "");
  const r = b.replace(/^0+(?=\d)/, "");
  if (l.length !== r.length) return l.length < r.length ? -1 : 1;
  return l === r ? 0 : l < r ? -1 : 1;
}

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

/** @returns {-1|0|1|null} null when either version is malformed. */
export function compareVersions(a, b) {
  const left = parse(a);
  const right = parse(b);
  if (!left || !right) return null;
  for (let i = 0; i < 3; i++) {
    const c = compareNumeric(left.core[i], right.core[i]);
    if (c !== 0) return c;
  }
  if (left.prerelease === right.prerelease) return 0;
  if (left.prerelease == null) return 1;
  if (right.prerelease == null) return -1;
  return comparePrerelease(left.prerelease, right.prerelease);
}

export function isValidVersion(value) {
  return parse(value) != null;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const result = compareVersions(process.argv[2], process.argv[3]);
  if (result == null) {
    console.error(`invalid version: ${process.argv[2]} / ${process.argv[3]}`);
    process.exit(2);
  }
  console.log(String(result));
}
