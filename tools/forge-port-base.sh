#!/usr/bin/env bash
#
# Refreshes lines of tools/forge-port-base.txt, the Forge port base read by
# tools/check-tree-drift.sh.
#
# Usage: tools/forge-port-base.sh update <path>...
#
#   <path> is repo-relative and must lie under src/main or src/test; a directory
#   refreshes every file under it. For each path, its old lines are dropped and
#   the current INDEX entries are recorded, so: stage the Forge change (and its
#   Ornithe port) first, then run this. A path no longer in the index simply
#   loses its line, which is how a removed Forge file is acknowledged.
#
# Only run this once the Forge change is ported to ornithe/ - the line is the
# record that it was. Exit: 0 = updated, 2 = refused (bad path, git failure).

set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root" || exit 2
base="tools/forge-port-base.txt"
die() { echo "error: $*" >&2; exit 2; }

[ "${1:-}" = update ] && [ $# -ge 2 ] || die "usage: tools/forge-port-base.sh update <path>..."
shift
[ -f "$base" ] || die "missing $base"

fmt=$(git rev-parse --show-object-format 2>/dev/null) || die "could not determine the object format"
case "$fmt" in sha1) HEXLEN=40 ;; sha256) HEXLEN=64 ;; *) die "unsupported object format '$fmt'" ;; esac

tmpd=$(mktemp -d) || exit 2
trap 'rm -rf "$tmpd"' EXIT

grep '^#' "$base" > "$tmpd/head"; st=$?; [ "$st" -le 1 ] || die "could not read $base"
awk '!/^#/ && NF' "$base" > "$tmpd/rows" || die "could not read $base"
: > "$tmpd/new"

for p in "$@"; do
  p=${p%/}
  case "$p" in
    src/main|src/main/*|src/test|src/test/*) ;;
    *) die "'$p' is not under src/main or src/test" ;;
  esac
  case "/$p/" in */../*|*/./*) die "'$p' must be a plain repo-relative path" ;; esac

  # Drop the path's old lines (the file itself, or everything under a directory).
  P=$p awk -v n="$HEXLEN" '{ q = substr($0, n + 2); p = ENVIRON["P"]
         if (q != p && substr(q, 1, length(p) + 1) != p "/") print }' \
    "$tmpd/rows" > "$tmpd/rows2" || die "could not filter $base"
  mv "$tmpd/rows2" "$tmpd/rows" || die "could not filter $base"

  # Record the current index entries. Literal pathspec, so no glob or magic in
  # a path name can widen the match.
  git --literal-pathspecs ls-files -s -z -- "$p" > "$tmpd/ls" || die "git ls-files failed for '$p'"
  LC_ALL=C tr -dc '\n' < "$tmpd/ls" > "$tmpd/nl" || die "could not read the index listing"
  [ ! -s "$tmpd/nl" ] || die "a path under '$p' contains a newline"
  tr '\000' '\n' < "$tmpd/ls" | sed -e '/^$/d' \
    -e 's/^[0-7]\{6\} \([0-9a-f]\{'"$HEXLEN"'\}\) [0-9]'$'\t''/\1 /' >> "$tmpd/new" \
    || die "could not parse the index listing for '$p'"
done

cat "$tmpd/rows" "$tmpd/new" > "$tmpd/all" || die "could not assemble $base"
LC_ALL=C sort -u -t ' ' -k2 "$tmpd/all" > "$tmpd/sorted" || die "could not sort $base"
cat "$tmpd/head" "$tmpd/sorted" > "$tmpd/out" || die "could not assemble $base"
cp "$tmpd/out" "$base" || die "could not write $base"
echo "updated $base ($(grep -vc '^#' "$base") lines)"
