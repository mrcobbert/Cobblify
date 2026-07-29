#!/usr/bin/env bash
#
# Fails when the Forge tree and the Lunar tree diverge in a way that
# tools/tree-divergence.txt does not declare.
#
# Comparison uses the git index: each tracked path's <mode, object id> pair.
#
#   * Two paths hold identical content exactly when their object ids match, so
#     nothing reads file content and there is no encoding question. (A source
#     file here once carried a raw NUL, which made git itself classify it as
#     binary and hid a real difference from every text-based tool.)
#   * The mode is part of the key, so a symlink and a regular file are never
#     equal even when git stores the same bytes for both - a link whose target
#     text is `foo` and a file containing `foo` share an object but are entirely
#     different compiler inputs.
#   * Nothing dereferences a path, so a symlinked parent directory cannot make
#     two different tracked files appear equal.
#
# Object-id equality means byte equality only while nothing rewrites content
# between index and worktree. Rather than assume that, the effective `filter`,
# `text` and `eol` attributes of every compared path are queried with
# `git check-attr` (which honours .gitattributes, .git/info/attributes and
# core.attributesFile alike), and core.autocrlf/core.eol are read. Anything that
# could rewrite bytes makes this refuse to answer instead of guessing.
#
# Every git and text-tool invocation is status-checked. A tool that fails must
# never degrade into "empty input, therefore nothing to compare, therefore OK" -
# a detector that passes because it checked nothing is worse than no detector.
#
# Usage: tools/check-tree-drift.sh
# Exit:  0 = no undeclared drift
#        1 = drift found
#        2 = cannot check (git or tool failure, unusable path, content filters
#            active, unmerged index, unsupported object format, bad manifest)
#
# Scope: the git index. An untracked or unstaged change is not compared until it
# is staged; the run says so. What this cannot catch at all: one commit editing
# both copies differently. That is a review problem, not a tooling one.

set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root" || exit 2

manifest="tools/tree-divergence.txt"
[ -f "$manifest" ] || { echo "error: missing $manifest" >&2; exit 2; }

# One temp directory, trapped immediately, so a later allocation failure cannot
# leak an earlier file.
tmpd=$(mktemp -d) || exit 2
trap 'rm -rf "$tmpd"' EXIT

norm="$tmpd/norm"; raw="$tmpd/raw"; tmp1="$tmpd/t1"; union="$tmpd/union"
main_f="$tmpd/main_f"; main_l="$tmpd/main_l"
test_f="$tmpd/test_f"; test_l="$tmpd/test_l"

TREES="src/main/java lunar/src/main/java src/test/java lunar/src/test/java"

failures=0
fail() { printf '  FAIL  %s\n' "$*"; failures=$((failures + 1)); }
die()  { echo "error: $*" >&2; exit 2; }

# --- object format: the inventory layout depends on the id width -------------

fmt=$(git rev-parse --show-object-format 2>/dev/null) \
  || die "could not determine the repository object format"
case "$fmt" in
  sha1)   HEXLEN=40 ;;
  sha256) HEXLEN=64 ;;
  *)      die "unsupported object format '$fmt'" ;;
esac
PREFIX_LEN=$((6 + 1 + HEXLEN + 1))   # "100644 " + <id> + " "

# --- premise guards ---------------------------------------------------------

# An unmerged index holds several rows per path (stages 1-3). A first-match
# lookup over those would be arbitrary and could compare a shared base row while
# ignoring the differing sides, so refuse instead.
git ls-files -u -- $TREES > "$tmp1" || die "could not check for an unmerged index"
[ ! -s "$tmp1" ] || die "the index has unmerged entries; resolve the merge before checking drift"

for cfg in core.autocrlf core.eol; do
  v=$(git config --get "$cfg" 2>/dev/null); st=$?
  [ "$st" -le 1 ] || die "could not read git config $cfg"
  if [ "$st" -eq 0 ]; then
    case "$v" in
      false|native) ;;
      *) die "$cfg=$v rewrites bytes between index and worktree; cannot compare safely." ;;
    esac
  fi
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

# --- inventories: "<mode> <id> <path>", one per line ------------------------
#
# `git ls-files -s -z` emits "<mode> <id> <stage>\t<path>\0". -z is required:
# without it git C-quotes any non-ASCII path, and a quoted string is not the
# path, so the file would silently drop out of the comparison.
#
# A newline inside a path cannot survive this line-oriented form, so it is
# rejected. Detecting that with grep would not work - grep treats a newline as a
# record separator, so a bracket expression can never match the character in
# question - hence the byte extraction. Every stage writes to a file and is
# status-checked, so no lossy step can pass silently.

list_tree() { # <dir> <outfile>
  local dir=$1 out=$2 want got
  git ls-files -s -z -- "$dir" > "$raw" || return 1

  LC_ALL=C tr -dc '\n' < "$raw" > "$tmpd/nl" || return 3
  [ ! -s "$tmpd/nl" ] || return 2

  LC_ALL=C tr -dc '\000' < "$raw" > "$tmpd/nul" || return 3
  want=$(wc -c < "$tmpd/nul") || return 3
  want=${want//[[:space:]]/}

  tr '\000' '\n' < "$raw" > "$tmpd/lines" || return 3
  sed -e '/^$/d' \
      -e 's/^\([0-7]\{6\}\) \([0-9a-f]\{'"$HEXLEN"'\}\) [0-9]'$'\t''/\1 \2 /' \
      -e "s|^\(.\{$PREFIX_LEN\}\)$dir/|\1|" "$tmpd/lines" > "$tmpd/stripped" || return 3
  LC_ALL=C sort "$tmpd/stripped" > "$out" || return 3

  got=$(wc -l < "$out") || return 3
  got=${got//[[:space:]]/}
  [ "$want" = "$got" ] || return 4

  # Every surviving row must have been rewritten to "<mode> <id> " form; if the
  # prefix substitution missed any line the layout assumption is wrong.
  if [ -s "$out" ] && ! awk -v n="$PREFIX_LEN" \
       'substr($0,1,7) !~ /^[0-7]{6} $/ || length($0) < n { bad=1; exit } END { exit bad?1:0 }' "$out"; then
    return 5
  fi
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
    3) die "an inventory transformation failed for $d (tr/sed/sort/wc)" ;;
    4) die "inventory row count mismatch for $d; refusing to report on partial data" ;;
    5) die "unexpected index row layout for $d; refusing to guess" ;;
  esac
done

# --- content-filter guard (after path validation) ---------------------------
#
# This runs after the inventories, not before: `git check-attr -z` emits a flat
# (path, attribute, value) stream, and parsing it positionally requires that no
# path contains a newline. The inventory step above has already established that,
# so the triples cannot be misaligned here.

# Effective attributes, not the presence of a file: .gitattributes,
# .git/info/attributes and core.attributesFile can all assign a filter, and a
# filter that is merely configured but never assigned is harmless.
git ls-files -z -- $TREES > "$raw" || die "could not list the compared trees"
if [ -s "$raw" ]; then
  git check-attr -z --stdin filter text eol < "$raw" > "$tmp1" \
    || die "git check-attr failed; cannot establish whether content filters are active"
  LC_ALL=C tr '\000' '\n' < "$tmp1" > "$union" || die "could not read git check-attr output"
  # -z output is a flat stream of (path, attribute, value) triples.
  if awk 'NR%3==0 && $0 != "unspecified" { print; exit }' "$union" | grep -q .; then
    offenders=$(awk 'NR%3==1{p=$0} NR%3==2{a=$0} NR%3==0 && $0!="unspecified"{print "  " p " -> " a "=" $0}' "$union" | head -5)
    die "content filters or EOL attributes apply to compared paths, so index object ids
       no longer imply identical checked-out bytes:
$offenders
       Teach this check to honour attributes before trusting it here."
  fi
fi

forge_list() { [ "$1" = main ] && echo "$main_f" || echo "$test_f"; }
lunar_list() { [ "$1" = main ] && echo "$main_l" || echo "$test_l"; }

# The path goes through the environment, NOT `awk -v`: -v assignments interpret
# backslash escapes, so a path literally containing `\t` would become a tab and
# never match its own inventory row.
key_of() {
  P=$2 awk -v pl="$PREFIX_LEN" \
    'substr($0,pl+1)==ENVIRON["P"] { print substr($0,1,pl-1); exit }' "$1"
}
paths_of() { cut -c$((PREFIX_LEN + 1))- "$1"; }

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
# Inventories sort by mode+id, so extracted paths must be re-sorted before comm;
# otherwise comm silently under-counts and the summary understates how much was
# actually compared.

count_pairs() { # <forge list> <lunar list>
  paths_of "$1" > "$tmpd/ca" || return 1
  LC_ALL=C sort "$tmpd/ca" > "$tmpd/ca.s" || return 1
  paths_of "$2" > "$tmpd/cb" || return 1
  LC_ALL=C sort "$tmpd/cb" > "$tmpd/cb.s" || return 1
  comm -12 "$tmpd/ca.s" "$tmpd/cb.s" > "$tmpd/cc" || return 1
  local n; n=$(wc -l < "$tmpd/cc") || return 1
  printf '%s' "${n//[[:space:]]/}"
}
pairs=$(count_pairs "$main_f" "$main_l") || die "could not count mirrored main pairs"
tpairs=$(count_pairs "$test_f" "$test_l") || die "could not count mirrored test pairs"
decl=$(grep -c . "$norm")

# This check reads the index, so a purely local edit is invisible to it. In CI
# that is a distinction without a difference. Run by hand it would otherwise look
# reassuring while comparing the previous content, so say so - and if the probe
# itself fails, say that rather than implying there were no local edits.
if git diff --name-only -- $TREES > "$tmp1" 2>/dev/null; then
  unstaged=$(grep -c . "$tmp1")
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
