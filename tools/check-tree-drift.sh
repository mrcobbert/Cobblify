#!/usr/bin/env bash
#
# Fails when the Forge tree and the Lunar tree diverge in a way that
# tools/tree-divergence.txt does not declare.
#
# Comparison uses the git index: each tracked path's <mode, blob hash> pair.
# That is the whole trick, and it is worth stating plainly:
#
#   * Two paths hold identical content exactly when their blob hashes match, so
#     nothing reads file content and there is no encoding question. (A source
#     file here once carried a raw NUL, which made git itself classify it as
#     binary and hid a real difference from every text-based tool.)
#   * The mode is part of the key, so a symlink and a regular file are never
#     equal even when git stores the same bytes for both - a link whose target
#     text is `foo` and a file containing `foo` share a blob but are entirely
#     different compiler inputs.
#   * Nothing dereferences a path, so a symlinked parent directory cannot make
#     two different tracked files appear equal. Symlinks need no special case:
#     git stores link text as the blob, so different targets differ here too.
#   * The index is what CI just checked out, and it includes staged changes, so
#     a local pre-commit run sees what the commit will contain.
#
# Blob equality equals byte equality only while no checkout filter or EOL
# normalisation is in play; a clean/smudge filter could store one blob and check
# out two different files. That is guarded explicitly below rather than assumed.
#
# Usage: tools/check-tree-drift.sh
# Exit:  0 = no undeclared drift
#        1 = drift found
#        2 = cannot check (git failure, unusable path, filters active, bad manifest)
#
# Scope: the git index. An untracked or unstaged change is not compared until it
# is staged; the run says so. What this cannot catch at all: one commit editing
# both copies differently. That is a review problem, not a tooling one.

set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root" || exit 2

manifest="tools/tree-divergence.txt"
[ -f "$manifest" ] || { echo "error: missing $manifest" >&2; exit 2; }

norm=$(mktemp) || exit 2
main_f=$(mktemp) || exit 2; main_l=$(mktemp) || exit 2
test_f=$(mktemp) || exit 2; test_l=$(mktemp) || exit 2
raw=$(mktemp) || exit 2; tmp1=$(mktemp) || exit 2; union=$(mktemp) || exit 2
trap 'rm -f "$norm" "$main_f" "$main_l" "$test_f" "$test_l" "$raw" "$tmp1" "$union"' EXIT

failures=0
fail() { printf '  FAIL  %s\n' "$*"; failures=$((failures + 1)); }
die()  { echo "error: $*" >&2; exit 2; }

# --- guard: checkout filters would break the blob-equality premise ----------

if [ -n "$(git ls-files -- '.gitattributes' '*/.gitattributes' 2>/dev/null)" ]; then
  die ".gitattributes is present. Blob hashes may no longer equal checked-out bytes
       (clean/smudge filters, text=auto, eol). This check must be taught to honour
       attributes before it can be trusted here."
fi
for cfg in core.autocrlf core.eol; do
  v=$(git config --get "$cfg" 2>/dev/null)
  case "$v" in
    ""|false|native) ;;
    *) die "$cfg=$v changes checked-out bytes relative to the stored blob; cannot compare safely." ;;
  esac
done

# --- manifest -> greppable keys ---------------------------------------------

sed 's/#.*//' "$manifest" > "$tmp1" || die "could not read $manifest"
awk 'NF' "$tmp1" > "$raw" || die "could not parse $manifest"
awk '
  $1 == "divergent" && NF == 3 { print "divergent|" $2 "|" $3; next }
  $1 == "one-sided" && NF == 4 { print "one-sided|" $2 "|" $3 "|" $4; next }
  { print "BADLINE|" $0 }
' "$raw" > "$norm" || die "could not normalise $manifest"

if grep -q '^BADLINE|' "$norm"; then
  echo "error: malformed lines in $manifest:" >&2
  sed -n 's/^BADLINE|/  /p' "$norm" >&2
  exit 2
fi
[ -s "$norm" ] || die "manifest normalised to nothing; refusing to check"

# --- inventories: "<mode> <sha> <path>", one per line -----------------------
#
# `git ls-files -s -z` emits "<mode> <sha> <stage>\t<path>\0". -z is required:
# without it git C-quotes any non-ASCII path, and a quoted string is not the
# path, so the file would silently drop out of the comparison.
#
# A newline inside a path is the one thing this line-oriented form cannot carry,
# so it is rejected. Detecting that with grep would not work - grep treats a
# newline as a record separator, so a bracket expression can never match the
# character in question - hence the byte count.
#
# Every stage is status-checked and the row count is verified against the number
# of NUL records, so a failing `sed`/`tr`/`sort` cannot quietly yield an empty
# inventory and a clean report.

PREFIX_LEN=48   # "100644 " (7) + 40-char sha + " " => path starts at column 49

list_tree() { # <dir> <outfile>
  local dir=$1 out=$2 want got
  git ls-files -s -z -- "$dir" > "$raw" || return 1
  [ "$(LC_ALL=C tr -dc '\n' < "$raw" | wc -c | tr -d ' ')" = "0" ] || return 2
  want=$(LC_ALL=C tr -dc '\000' < "$raw" | wc -c | tr -d ' ')
  tr '\000' '\n' < "$raw" > "$tmp1" || return 3
  sed -e '/^$/d' \
      -e 's/^\([0-7]\{6\}\) \([0-9a-f]\{40\}\) [0-9]'$'\t''/\1 \2 /' \
      -e "s|^\(.\{$PREFIX_LEN\}\)$dir/|\1|" "$tmp1" > "$out" || return 3
  LC_ALL=C sort "$out" > "$tmp1" || return 3
  cp "$tmp1" "$out" || return 3
  got=$(wc -l < "$out" | tr -d ' ')
  [ "$want" = "$got" ] || return 4
  return 0
}

for spec in "src/main/java:$main_f" "lunar/src/main/java:$main_l" \
            "src/test/java:$test_f" "lunar/src/test/java:$test_l"; do
  d=${spec%:*}; o=${spec##*:}
  list_tree "$d" "$o"
  case $? in
    0) ;;
    1) die "could not enumerate $d (git ls-files failed)" ;;
    2) die "a tracked path under $d contains a newline; cannot check" ;;
    3) die "an inventory transformation failed for $d (sed/tr/sort)" ;;
    4) die "inventory row count mismatch for $d; refusing to report on partial data" ;;
  esac
done

forge_list() { [ "$1" = main ] && echo "$main_f" || echo "$test_f"; }
lunar_list() { [ "$1" = main ] && echo "$main_l" || echo "$test_l"; }

# The path is passed through the environment, NOT `awk -v`: -v assignments
# interpret backslash escapes, so a path literally containing `\t` would be
# turned into a tab and never match its own inventory row.
key_of() { P=$2 awk 'substr($0,'"$((PREFIX_LEN+1))"')==ENVIRON["P"] { print substr($0,1,'"$((PREFIX_LEN-1))"'); exit }' "$1"; }
paths_of() { cut -c$((PREFIX_LEN+1))- "$1"; }

declared_divergent() { grep -Fxq "divergent|$1|$2" "$norm"; }
declared_side() {
  grep -Fxq "one-sided|$1|forge|$2" "$norm" && { echo forge; return; }
  grep -Fxq "one-sided|$1|lunar|$2" "$norm" && { echo lunar; return; }
  echo ""
}

# --- pass A: everything in either index must be declared or identical -------

for tree in main test; do
  { paths_of "$(forge_list "$tree")"; paths_of "$(lunar_list "$tree")"; } > "$tmp1" \
    || die "could not build the $tree path list"
  LC_ALL=C sort -u "$tmp1" > "$union" || die "sort failed while building the $tree path union"
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    fk=$(key_of "$(forge_list "$tree")" "$p")
    lk=$(key_of "$(lunar_list "$tree")" "$p")
    side=$(declared_side "$tree" "$p")

    if [ -n "$fk" ] && [ -n "$lk" ]; then
      if [ "$fk" != "$lk" ] && ! declared_divergent "$tree" "$p"; then
        fail "$tree: $p differs between trees but is not declared. Add 'divergent $tree $p' if intended."
      fi
    elif [ -n "$fk" ]; then
      [ -n "$side" ] || fail "$tree: $p exists only in the Forge tree and is not declared. Add 'one-sided $tree forge $p', or restore the Lunar copy."
    elif [ -n "$lk" ]; then
      [ -n "$side" ] || fail "$tree: $p exists only in the Lunar tree and is not declared. Add 'one-sided $tree lunar $p', or restore the Forge copy."
    fi
  done < "$union"
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
    fk=$(key_of "$(forge_list "$tree")" "$p")
    lk=$(key_of "$(lunar_list "$tree")" "$p")
    if [ -z "$fk" ] || [ -z "$lk" ]; then
      fail "manifest: 'divergent $tree $p' but the file is missing from $([ -z "$fk" ] && echo Forge || echo Lunar). Remove the entry, or declare it one-sided."
    elif [ "$fk" = "$lk" ]; then
      fail "manifest: 'divergent $tree $p' but the two copies are now identical. Remove the stale entry."
    fi
  else
    want=${rest%%|*}; p=${rest#*|}
    case "$want" in
      forge|lunar) ;;
      *) fail "manifest: unknown side '$want' in: $line"; continue ;;
    esac
    fk=$(key_of "$(forge_list "$tree")" "$p")
    lk=$(key_of "$(lunar_list "$tree")" "$p")
    if [ -n "$fk" ] && [ -n "$lk" ]; then
      fail "manifest: 'one-sided $tree $want $p' but it now exists in BOTH trees. Remove the stale entry, or delete the unintended copy."
    elif [ -z "$fk" ] && [ -z "$lk" ]; then
      fail "manifest: 'one-sided $tree $want $p' but the file exists in NEITHER tree. Remove the stale entry."
    elif [ "$want" = forge ] && [ -z "$fk" ]; then
      fail "manifest: 'one-sided $tree forge $p' but the file is in the Lunar tree instead. It changed sides."
    elif [ "$want" = lunar ] && [ -z "$lk" ]; then
      fail "manifest: 'one-sided $tree lunar $p' but the file is in the Forge tree instead. It changed sides."
    fi
  fi
done < "$norm"

# --- report -----------------------------------------------------------------
#
# The inventories sort by mode+sha, so extracted paths must be re-sorted before
# comm; otherwise comm silently under-counts and the summary understates how
# much was actually compared.

count_pairs() { # <forge list> <lunar list>
  paths_of "$1" | LC_ALL=C sort > "$tmp1" || return 1
  paths_of "$2" | LC_ALL=C sort > "$union" || return 1
  comm -12 "$tmp1" "$union" | wc -l | tr -d ' '
}
pairs=$(count_pairs "$main_f" "$main_l") || die "could not count mirrored main pairs"
tpairs=$(count_pairs "$test_f" "$test_l") || die "could not count mirrored test pairs"
decl=$(grep -c . "$norm")

# This check reads the index, so a purely local edit is invisible to it. In CI
# that is a distinction without a difference. Run by hand it would otherwise
# look reassuring while comparing the previous content, so say so - and if the
# probe itself fails, say that rather than silently implying "no local edits".
if unstaged_out=$(git diff --name-only -- \
     src/main/java lunar/src/main/java src/test/java lunar/src/test/java 2>/dev/null); then
  unstaged=$(printf '%s' "$unstaged_out" | grep -c . )
  if [ "$unstaged" != "0" ]; then
    echo "  note: $unstaged compared file(s) have unstaged edits. This check reads the git index,"
    echo "        so those edits were NOT compared. Stage them and re-run to include them."
  fi
else
  echo "  warning: could not determine whether there are unstaged edits (git diff failed);"
  echo "           this run reflects the index only."
fi

if [ "$failures" -eq 0 ]; then
  echo "tree drift check: OK ($pairs mirrored main pairs, $tpairs mirrored test pairs, $decl declared exceptions)"
  echo "  scope: git index contents - an untracked or unstaged change is not compared until it is staged."
  exit 0
fi

echo
echo "tree drift check: $failures problem(s). See tools/tree-divergence.txt."
exit 1
