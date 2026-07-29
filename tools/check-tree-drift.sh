#!/usr/bin/env bash
#
# Fails when the Forge tree and the Lunar tree diverge in a way that
# tools/tree-divergence.txt does not declare.
#
# Comparison is by BYTES (cmp), never by diff/grep: a source file here once
# carried a raw NUL, which made git itself classify it as binary and hid a real
# difference from every text-based tool.
#
# Usage: tools/check-tree-drift.sh
# Exit:  0 = no undeclared drift, 1 = drift found, 2 = bad manifest/usage.
#
# What this CANNOT catch: one commit editing both copies differently. That is a
# review problem, not a tooling one.

set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root" || exit 2

manifest="tools/tree-divergence.txt"
[ -f "$manifest" ] || { echo "error: missing $manifest" >&2; exit 2; }

norm=$(mktemp) || exit 2
main_f=$(mktemp) || exit 2; main_l=$(mktemp) || exit 2
test_f=$(mktemp) || exit 2; test_l=$(mktemp) || exit 2
trap 'rm -f "$norm" "$main_f" "$main_l" "$test_f" "$test_l"' EXIT

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

# --- tree inventories (index contents, so staged adds are seen too) ---------

list_tree() { git ls-files "$1" | sed "s|^$1/||" | sort; }
list_tree src/main/java        > "$main_f"
list_tree lunar/src/main/java  > "$main_l"
list_tree src/test/java        > "$test_f"
list_tree lunar/src/test/java  > "$test_l"

forge_dir() { [ "$1" = main ] && echo src/main/java || echo src/test/java; }
lunar_dir() { [ "$1" = main ] && echo lunar/src/main/java || echo lunar/src/test/java; }
forge_list() { [ "$1" = main ] && echo "$main_f" || echo "$test_f"; }
lunar_list() { [ "$1" = main ] && echo "$main_l" || echo "$test_l"; }

# Candidate paths come from the git index (deterministic, ignores build output),
# but presence is judged on disk so a plain `rm` reports as a deletion rather
# than as a failed compare. An untracked new file is invisible here; it becomes
# visible as soon as it is staged.
in_forge() { [ -f "$(forge_dir "$1")/$2" ]; }
in_lunar() { [ -f "$(lunar_dir "$1")/$2" ]; }
declared_divergent() { grep -Fxq "divergent|$1|$2" "$norm"; }
declared_side() {
  grep -Fxq "one-sided|$1|forge|$2" "$norm" && { echo forge; return; }
  grep -Fxq "one-sided|$1|lunar|$2" "$norm" && { echo lunar; return; }
  echo ""
}

# --- pass A: everything actually on disk must be declared or identical ------

for tree in main test; do
  fdir=$(forge_dir "$tree"); ldir=$(lunar_dir "$tree")
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    f=0; in_forge "$tree" "$p" && f=1
    l=0; in_lunar "$tree" "$p" && l=1
    side=$(declared_side "$tree" "$p")

    if [ "$f" = 1 ] && [ "$l" = 1 ]; then
      if ! cmp -s "$fdir/$p" "$ldir/$p"; then
        if ! declared_divergent "$tree" "$p"; then
          fail "$tree: $p differs between trees but is not declared. Add 'divergent $tree $p' if intended."
        fi
      fi
    elif [ "$f" = 1 ]; then
      [ -n "$side" ] || fail "$tree: $p exists only in the Forge tree and is not declared. Add 'one-sided $tree forge $p', or restore the Lunar copy."
    elif [ "$l" = 1 ]; then
      [ -n "$side" ] || fail "$tree: $p exists only in the Lunar tree and is not declared. Add 'one-sided $tree lunar $p', or restore the Forge copy."
    fi
    # f=0 && l=0: tracked but gone from both trees - a deletion, not drift.
  done < <(sort -u "$(forge_list "$tree")" "$(lunar_list "$tree")")
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

  fdir=$(forge_dir "$tree"); ldir=$(lunar_dir "$tree")

  if [ "$kind" = divergent ]; then
    p=$rest
    f=0; in_forge "$tree" "$p" && f=1
    l=0; in_lunar "$tree" "$p" && l=1
    if [ "$f" = 0 ] || [ "$l" = 0 ]; then
      fail "manifest: 'divergent $tree $p' but the file is missing from $([ "$f" = 0 ] && echo Forge || echo Lunar). Remove the entry, or declare it one-sided."
    elif cmp -s "$fdir/$p" "$ldir/$p"; then
      fail "manifest: 'divergent $tree $p' but the two copies are now identical. Remove the stale entry."
    fi
  else
    want=${rest%%|*}; p=${rest#*|}
    case "$want" in
      forge|lunar) ;;
      *) fail "manifest: unknown side '$want' in: $line"; continue ;;
    esac
    f=0; in_forge "$tree" "$p" && f=1
    l=0; in_lunar "$tree" "$p" && l=1
    if [ "$f" = 1 ] && [ "$l" = 1 ]; then
      fail "manifest: 'one-sided $tree $want $p' but it now exists in BOTH trees. Remove the stale entry, or delete the unintended copy."
    elif [ "$f" = 0 ] && [ "$l" = 0 ]; then
      fail "manifest: 'one-sided $tree $want $p' but the file exists in NEITHER tree. Remove the stale entry."
    elif [ "$want" = forge ] && [ "$f" = 0 ]; then
      fail "manifest: 'one-sided $tree forge $p' but the file is in the Lunar tree instead. It changed sides."
    elif [ "$want" = lunar ] && [ "$l" = 0 ]; then
      fail "manifest: 'one-sided $tree lunar $p' but the file is in the Forge tree instead. It changed sides."
    fi
  fi
done < "$norm"

# --- report -----------------------------------------------------------------

pairs=$(comm -12 "$main_f" "$main_l" | wc -l | tr -d ' ')
tpairs=$(comm -12 "$test_f" "$test_l" | wc -l | tr -d ' ')
decl=$(grep -c . "$norm")

if [ "$failures" -eq 0 ]; then
  echo "tree drift check: OK ($pairs mirrored main pairs, $tpairs mirrored test pairs, $decl declared exceptions)"
  exit 0
fi

echo
echo "tree drift check: $failures problem(s). See tools/tree-divergence.txt."
exit 1
