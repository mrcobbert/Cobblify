#!/usr/bin/env bash
#
# Fails when the Forge tree and the Lunar tree diverge in a way that
# tools/tree-divergence.txt does not declare.
#
# Comparison is on git BLOB HASHES from the index, not on files read off disk.
# That is the whole trick here, and it is worth stating plainly:
#
#   * Two paths are byte-identical exactly when their blob hashes match, so
#     there is no content read, no encoding question, and no NUL problem. (A
#     source file here once carried a raw NUL, which made git itself classify
#     it as binary and hid a real difference from every text-based tool.)
#   * Symlinks need no special case: git stores the link text as the blob, so
#     two links with different targets simply have different hashes.
#   * Nothing dereferences a path, so a symlinked parent directory cannot make
#     two different tracked files appear equal.
#   * The index is also what CI has just checked out, and it includes staged
#     changes, so a local pre-commit run sees what the commit will contain.
#
# Usage: tools/check-tree-drift.sh
# Exit:  0 = no undeclared drift
#        1 = drift found
#        2 = cannot check (git failure, unusable path, malformed manifest)
#
# Scope: git-tracked files only. An untracked new file is invisible until it is
# staged. What this CANNOT catch at all: one commit editing both copies
# differently. That is a review problem, not a tooling one.

set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root" || exit 2

manifest="tools/tree-divergence.txt"
[ -f "$manifest" ] || { echo "error: missing $manifest" >&2; exit 2; }

norm=$(mktemp) || exit 2
main_f=$(mktemp) || exit 2; main_l=$(mktemp) || exit 2
test_f=$(mktemp) || exit 2; test_l=$(mktemp) || exit 2
scratch=$(mktemp) || exit 2
trap 'rm -f "$norm" "$main_f" "$main_l" "$test_f" "$test_l" "$scratch"' EXIT

failures=0
fail() { printf '  FAIL  %s\n' "$*"; failures=$((failures + 1)); }

# --- manifest -> greppable keys ---------------------------------------------

sed 's/#.*//' "$manifest" | awk 'NF' | awk '
  $1 == "divergent" && NF == 3 { print "divergent|" $2 "|" $3; next }
  $1 == "one-sided" && NF == 4 { print "one-sided|" $2 "|" $3 "|" $4; next }
  { print "BADLINE|" $0 }
' > "$norm"

if grep -q '^BADLINE|' "$norm"; then
  echo "error: malformed lines in $manifest:" >&2
  sed -n 's/^BADLINE|/  /p' "$norm" >&2
  exit 2
fi

# --- inventories: "<40-char blob sha> <path>", one per line -----------------
#
# `git ls-files -s -z` emits "<mode> <sha> <stage>\t<path>\0". -z is required:
# without it git C-quotes any non-ASCII path, and a quoted string is not the
# path, so the file would drop out of the comparison entirely.
#
# A newline inside a path is the one thing the line-oriented form below cannot
# represent, so it is rejected outright. Checking that with `grep` would not
# work - grep treats a newline as a record separator, so a bracket expression
# can never match the very character in question - hence the byte count. Tabs
# and every other character are fine: the path is taken as everything after the
# first tab, and parsed by position, not by field splitting.

list_tree() { # <dir> <outfile>
  local dir=$1 out=$2 rc
  git ls-files -s -z -- "$dir" > "$scratch"; rc=$?
  [ "$rc" -eq 0 ] || return 1
  [ "$(LC_ALL=C tr -dc '\n' < "$scratch" | wc -c | tr -d ' ')" = "0" ] || return 2
  tr '\000' '\n' < "$scratch" \
    | sed -e '/^$/d' -e 's/^[0-7]\{6\} \([0-9a-f]\{40\}\) [0-9]'$'\t''/\1 /' \
    | sed "s| $dir/| |" \
    | LC_ALL=C sort > "$out"
  return 0
}

for spec in "src/main/java:$main_f" "lunar/src/main/java:$main_l" \
            "src/test/java:$test_f" "lunar/src/test/java:$test_l"; do
  d=${spec%:*}; o=${spec##*:}
  list_tree "$d" "$o"
  case $? in
    0) ;;
    2) echo "error: a tracked path under $d contains a newline; cannot check" >&2; exit 2 ;;
    *) echo "error: could not enumerate $d (git ls-files failed)" >&2; exit 2 ;;
  esac
done

# An empty inventory is NOT treated as infrastructure failure: git succeeded, so
# an empty tree is a real index state (every file deleted or moved), and that is
# drift for the passes below to report - not a reason to refuse to look.

forge_dir() { [ "$1" = main ] && echo src/main/java || echo src/test/java; }
lunar_dir() { [ "$1" = main ] && echo lunar/src/main/java || echo lunar/src/test/java; }
forge_list() { [ "$1" = main ] && echo "$main_f" || echo "$test_f"; }
lunar_list() { [ "$1" = main ] && echo "$main_l" || echo "$test_l"; }

# Exact match on the path portion (everything from column 42), so a path can
# contain spaces, tabs or unicode without confusing the lookup.
sha_of() { awk -v p="$2" 'substr($0,42)==p { print substr($0,1,40); exit }' "$1"; }
paths_of() { cut -c42- "$1"; }

declared_divergent() { grep -Fxq "divergent|$1|$2" "$norm"; }
declared_side() {
  grep -Fxq "one-sided|$1|forge|$2" "$norm" && { echo forge; return; }
  grep -Fxq "one-sided|$1|lunar|$2" "$norm" && { echo lunar; return; }
  echo ""
}

# --- pass A: everything in either index must be declared or identical -------

for tree in main test; do
  paths_of "$(forge_list "$tree")" >  "$scratch"
  paths_of "$(lunar_list "$tree")" >> "$scratch"
  if ! LC_ALL=C sort -u "$scratch" > "$scratch.u"; then
    echo "error: sort failed while building the $tree path union" >&2; exit 2
  fi
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    fs=$(sha_of "$(forge_list "$tree")" "$p")
    ls=$(sha_of "$(lunar_list "$tree")" "$p")
    side=$(declared_side "$tree" "$p")

    if [ -n "$fs" ] && [ -n "$ls" ]; then
      if [ "$fs" != "$ls" ] && ! declared_divergent "$tree" "$p"; then
        fail "$tree: $p differs between trees but is not declared. Add 'divergent $tree $p' if intended."
      fi
    elif [ -n "$fs" ]; then
      [ -n "$side" ] || fail "$tree: $p exists only in the Forge tree and is not declared. Add 'one-sided $tree forge $p', or restore the Lunar copy."
    elif [ -n "$ls" ]; then
      [ -n "$side" ] || fail "$tree: $p exists only in the Lunar tree and is not declared. Add 'one-sided $tree lunar $p', or restore the Forge copy."
    fi
  done < "$scratch.u"
  rm -f "$scratch.u"
done

# --- pass B: every declared entry must still describe reality ---------------

while IFS= read -r line; do
  [ -n "$line" ] || continue
  kind=${line%%|*}; rest=${line#*|}
  tree=${rest%%|*}; rest=${rest#*|}

  case "$tree" in
    main|test) ;;
    *) fail "manifest: unknown tree '$tree' in: $line"; continue ;;
  esac

  if [ "$kind" = divergent ]; then
    p=$rest
    fs=$(sha_of "$(forge_list "$tree")" "$p")
    ls=$(sha_of "$(lunar_list "$tree")" "$p")
    if [ -z "$fs" ] || [ -z "$ls" ]; then
      fail "manifest: 'divergent $tree $p' but the file is missing from $([ -z "$fs" ] && echo Forge || echo Lunar). Remove the entry, or declare it one-sided."
    elif [ "$fs" = "$ls" ]; then
      fail "manifest: 'divergent $tree $p' but the two copies are now identical. Remove the stale entry."
    fi
  else
    want=${rest%%|*}; p=${rest#*|}
    case "$want" in
      forge|lunar) ;;
      *) fail "manifest: unknown side '$want' in: $line"; continue ;;
    esac
    fs=$(sha_of "$(forge_list "$tree")" "$p")
    ls=$(sha_of "$(lunar_list "$tree")" "$p")
    if [ -n "$fs" ] && [ -n "$ls" ]; then
      fail "manifest: 'one-sided $tree $want $p' but it now exists in BOTH trees. Remove the stale entry, or delete the unintended copy."
    elif [ -z "$fs" ] && [ -z "$ls" ]; then
      fail "manifest: 'one-sided $tree $want $p' but the file exists in NEITHER tree. Remove the stale entry."
    elif [ "$want" = forge ] && [ -z "$fs" ]; then
      fail "manifest: 'one-sided $tree forge $p' but the file is in the Lunar tree instead. It changed sides."
    elif [ "$want" = lunar ] && [ -z "$ls" ]; then
      fail "manifest: 'one-sided $tree lunar $p' but the file is in the Forge tree instead. It changed sides."
    fi
  fi
done < "$norm"

# --- report -----------------------------------------------------------------

# NB: the inventories are sorted by line, i.e. by blob sha, so the extracted
# paths must be re-sorted before comm - otherwise comm silently under-counts and
# the summary understates how much was actually compared.
pairs=$(comm -12 <(paths_of "$main_f" | LC_ALL=C sort) <(paths_of "$main_l" | LC_ALL=C sort) | wc -l | tr -d ' ')
tpairs=$(comm -12 <(paths_of "$test_f" | LC_ALL=C sort) <(paths_of "$test_l" | LC_ALL=C sort) | wc -l | tr -d ' ')
decl=$(grep -c . "$norm")

# This check reads the index, so a purely local edit is not visible to it. In CI
# that is a distinction without a difference (a fresh checkout has no unstaged
# work), but running it by hand after editing would otherwise look reassuring
# while comparing the previous content. Say so rather than quietly pass.
unstaged=$(git diff --name-only -- \
  src/main/java lunar/src/main/java src/test/java lunar/src/test/java 2>/dev/null | wc -l | tr -d ' ')
if [ "${unstaged:-0}" != "0" ]; then
  echo "  note: $unstaged compared file(s) have unstaged edits. This check reads the git index,"
  echo "        so those edits were NOT compared. Stage them and re-run to include them."
fi

if [ "$failures" -eq 0 ]; then
  echo "tree drift check: OK ($pairs mirrored main pairs, $tpairs mirrored test pairs, $decl declared exceptions)"
  echo "  scope: git index contents - an untracked or unstaged change is not compared until it is staged."
  exit 0
fi

echo
echo "tree drift check: $failures problem(s). See tools/tree-divergence.txt."
exit 1
