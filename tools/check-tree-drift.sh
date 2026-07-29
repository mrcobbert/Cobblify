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
#
# Enumeration is NUL-delimited. `git ls-files` without -z C-quotes any path that
# is not plain ASCII (core.quotePath), and a quoted string is not the path on
# disk - so a file named `Café.java` would silently drop out of the comparison
# and could carry undeclared drift straight through. -z never quotes.
#
# Every failure here is fatal (exit 2). Without that, a git error yields empty
# inventories and the script would cheerfully report "OK, 0 mirrored pairs" -
# a detector that passes because it checked nothing.

list_tree() { # <dir> <outfile>
  local dir=$1 out=$2 z rc
  z=$(mktemp) || return 1
  git ls-files -z -- "$dir" > "$z"; rc=$?
  if [ "$rc" -ne 0 ]; then rm -f "$z"; return 1; fi
  # A newline inside a filename cannot survive the line-oriented lists below.
  # There is no legitimate one in a Java source tree, so fail closed.
  if LC_ALL=C tr -d '\000' < "$z" | LC_ALL=C grep -q '[[:cntrl:]]'; then
    rm -f "$z"; return 2
  fi
  tr '\000' '\n' < "$z" | sed "s|^$dir/||" | sed '/^$/d' | LC_ALL=C sort > "$out"
  rm -f "$z"
  return 0
}

for spec in "src/main/java:$main_f" "lunar/src/main/java:$main_l" \
            "src/test/java:$test_f" "lunar/src/test/java:$test_l"; do
  d=${spec%:*}; o=${spec##*:}
  list_tree "$d" "$o"
  case $? in
    0) ;;
    2) echo "error: unsupported control character in a tracked path under $d" >&2; exit 2 ;;
    *) echo "error: could not enumerate $d (git ls-files failed)" >&2; exit 2 ;;
  esac
done

# src/main/java always has files. An empty inventory means the working directory,
# index or checkout is not what we think it is - refuse rather than pass.
if [ ! -s "$main_f" ]; then
  echo "error: src/main/java enumerated to nothing; refusing to report a clean tree" >&2
  exit 2
fi

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

# --- symlink guard ----------------------------------------------------------
#
# `[ -f ]` and `cmp` both follow symlinks, so two links whose tracked blobs
# differ (`-> ModChat.java` vs `-> ./ModChat.java`) would compare their
# identical targets and pass, and two broken links would read as absent from
# both trees. This detector promises to compare the tracked bytes, so a symlink
# it cannot honestly compare is an error rather than a silent pass. There are
# none in either tree today.

for tree in main test; do
  fdir=$(forge_dir "$tree"); ldir=$(lunar_dir "$tree")
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    for d in "$fdir" "$ldir"; do
      if [ -L "$d/$p" ]; then
        echo "error: symlink in a compared tree is not supported: $d/$p" >&2
        exit 2
      fi
    done
  done < <(LC_ALL=C sort -u "$(forge_list "$tree")" "$(lunar_list "$tree")")
done

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
  echo "  scope: git-tracked files only - an untracked new file is not compared until it is staged."
  exit 0
fi

echo
echo "tree drift check: $failures problem(s). See tools/tree-divergence.txt."
exit 1
