#!/usr/bin/env bash
#
# Fails when two platform trees diverge in a way their manifest does not
# declare, or when the Forge tree has moved since the Ornithe port was taken
# from it.
#
# Tree pairs, each with its own manifest (format in each manifest's header):
#
#   forge/lunar    src/          vs lunar/src/     tools/tree-divergence.txt
#   ornithe/lunar  ornithe/src/  vs lunar/src/     tools/tree-divergence-ornithe-lunar.txt
#   forge/ornithe  src/          vs ornithe/src/   tools/tree-divergence-forge-ornithe.txt
#
# The two Ornithe pairs are active only while the Ornithe tree exists: ornithe/src
# is present in the working tree or has entries in the index. Until then they are
# skipped, and their manifests must hold no entries (a declaration about a tree
# that does not exist is stale). Once active, a missing or empty manifest is an
# error, exactly as for forge/lunar.
#
# Within a pair three mirrored roots are compared: the main and test java roots
# and the main resource roots (the jars ship the same fonts, icons and CA bundle,
# so a one-sided asset edit is as real a divergence as a one-sided source edit).
#
# Forge port base guard: while tools/forge-port-base.txt exists, every file under
# src/main and src/test must have exactly the git blob id recorded there, and no
# Forge file may be added or removed. A pair check cannot catch a fix made inside
# a file declared divergent; this does. Refresh a line once the change is ported
# with tools/forge-port-base.sh update <path>...
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
# Exit:  0 = no undeclared drift, Forge tree at its port base
#        1 = drift found, or a Forge change not yet acknowledged as ported
#        2 = cannot check (git or tool failure, unusable path, content filters
#            active, unmerged index, unsupported object format, bad manifest or
#            bad port base file)
#
# A declared-divergent .java pair is checked once more, against a stripped copy
# of each side: if the two differ only in comments or whitespace the declaration
# is itself an error, because it licenses the pair to drift for real later. The
# strip filter is deliberately simple and deliberately one-sided - see
# strip_comments - so it can only make two copies look MORE different than they
# are, never less.
#
# Scope: the git index. An untracked or unstaged change is not compared until it
# is staged; the run says so. What this cannot catch at all: one commit editing
# both copies differently. That is a review problem, not a tooling one.

set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root" || exit 2

failures=0
fail() { printf '  FAIL  %s\n' "$*"; failures=$((failures + 1)); }
die()  { echo "error: $*" >&2; exit 2; }

manifest_fl="tools/tree-divergence.txt"
manifest_ol="tools/tree-divergence-ornithe-lunar.txt"
manifest_fo="tools/tree-divergence-forge-ornithe.txt"
port_base="tools/forge-port-base.txt"
[ -f "$manifest_fl" ] || die "missing $manifest_fl"

# One temp directory, trapped immediately, so a later allocation failure cannot
# leak an earlier file.
tmpd=$(mktemp -d) || exit 2
trap 'rm -rf "$tmpd"' EXIT

raw="$tmpd/raw"; tmp1="$tmpd/t1"; union="$tmpd/union"

TREES="src/main/java lunar/src/main/java src/test/java lunar/src/test/java \
       src/main/resources lunar/src/main/resources \
       ornithe/src/main/java ornithe/src/test/java ornithe/src/main/resources"
BASE_ROOTS="src/main src/test"

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
git ls-files -u -- $TREES $BASE_ROOTS > "$tmp1" || die "could not check for an unmerged index"
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

# The Ornithe tree exists if it is on disk or in the index. Either suffices: an
# index emptied of it while the directory remains must still be checked (and
# reported as drift), not quietly skipped.
ornithe_active=0
if [ -e ornithe/src ] || [ -L ornithe/src ]; then
  ornithe_active=1
else
  git ls-files -z -- ornithe/src > "$tmp1" || die "could not check whether the Ornithe tree exists"
  [ ! -s "$tmp1" ] || ornithe_active=1
fi

# --- manifests -> greppable keys --------------------------------------------

parse_manifest() { # <manifest> <out>
  sed 's/#.*//' "$1" > "$tmp1" || die "could not read $1"
  awk 'NF' "$tmp1" > "$raw" || die "could not parse $1"
  awk '
    $1 == "divergent" && NF == 3 { print "divergent|" $2 "|" $3; next }
    $1 == "one-sided" && NF == 4 { print "one-sided|" $2 "|" $3 "|" $4; next }
    { print "BADLINE|" $0 }
  ' "$raw" > "$2" || die "could not normalise $1"

  if grep -q '^BADLINE|' "$2"; then
    echo "error: malformed lines in $1:" >&2
    sed -n 's/^BADLINE|/  /p' "$2" >&2
    exit 2
  fi
}

# "<side a>:<side b>:<manifest>" for every pair that is checked this run.
PAIRS="forge:lunar:$manifest_fl"
skipped=""
if [ "$ornithe_active" -eq 1 ]; then
  PAIRS="$PAIRS ornithe:lunar:$manifest_ol forge:ornithe:$manifest_fo"
else
  for m in "$manifest_ol" "$manifest_fo"; do
    [ -f "$m" ] || continue
    parse_manifest "$m" "$tmpd/inactive"
    n=$(grep -c . "$tmpd/inactive")
    [ "$n" = "0" ] || fail "manifest: $m has $n entries but the Ornithe tree (ornithe/src) does not exist. Remove the stale entries."
  done
  skipped="ornithe/lunar forge/ornithe"
fi

for spec in $PAIRS; do
  a=${spec%%:*}; rest=${spec#*:}; b=${rest%%:*}; m=${rest#*:}
  [ -f "$m" ] || die "missing $m (needed for the $a/$b pair)"
  parse_manifest "$m" "$tmpd/norm_${a}_$b"
  [ -s "$tmpd/norm_${a}_$b" ] || die "manifest $m normalised to nothing; refusing to check"
done

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

list_tree() { # <dir> <outfile> [keep]  - "keep" leaves paths repo-relative
  local dir=$1 out=$2 keep=${3:-} want got strip
  git ls-files -s -z -- "$dir" > "$raw" || return 1

  LC_ALL=C tr -dc '\n' < "$raw" > "$tmpd/nl" || return 3
  [ ! -s "$tmpd/nl" ] || return 2

  LC_ALL=C tr -dc '\000' < "$raw" > "$tmpd/nul" || return 3
  want=$(wc -c < "$tmpd/nul") || return 3
  want=${want//[[:space:]]/}

  strip="s|^\(.\{$PREFIX_LEN\}\)$dir/|\1|"
  [ "$keep" = keep ] && strip='s|^||'
  tr '\000' '\n' < "$raw" > "$tmpd/lines" || return 3
  sed -e '/^$/d' \
      -e 's/^\([0-7]\{6\}\) \([0-9a-f]\{'"$HEXLEN"'\}\) [0-9]'$'\t''/\1 \2 /' \
      -e "$strip" "$tmpd/lines" > "$tmpd/stripped" || return 3
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

inventory() { # <dir> <outfile> [keep]
  list_tree "$@"
  case $? in
    0) ;;
    1) die "could not enumerate $1 (git ls-files failed)" ;;
    2) die "a tracked path under $1 contains a newline; cannot check" ;;
    3) die "an inventory transformation failed for $1 (tr/sed/sort/wc)" ;;
    4) die "inventory row count mismatch for $1; refusing to report on partial data" ;;
    5) die "unexpected index row layout for $1; refusing to guess" ;;
  esac
}

side_prefix() {
  case "$1" in
    forge)   echo "" ;;
    lunar)   echo "lunar/" ;;
    ornithe) echo "ornithe/" ;;
    *)       die "internal: unknown side '$1'" ;;
  esac
}
side_name() {
  case "$1" in
    forge)   echo Forge ;;
    lunar)   echo Lunar ;;
    ornithe) echo Ornithe ;;
    *)       die "internal: unknown side '$1'" ;;
  esac
}
tree_root() { # <tree>
  case "$1" in
    main) echo "src/main/java" ;;
    test) echo "src/test/java" ;;
    res)  echo "src/main/resources" ;;
    *)    die "internal: unknown tree '$1'" ;;
  esac
}
inv() { echo "$tmpd/inv_$1_$2"; }   # <side> <tree>

SIDES="forge lunar"
[ "$ornithe_active" -eq 1 ] && SIDES="$SIDES ornithe"
for s in $SIDES; do
  for t in main test res; do
    inventory "$(side_prefix "$s")$(tree_root "$t")" "$(inv "$s" "$t")"
  done
done
if [ -f "$port_base" ]; then
  inventory src/main "$tmpd/base_main" keep
  inventory src/test "$tmpd/base_test" keep
fi

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

# The path goes through the environment, NOT `awk -v`: -v assignments interpret
# backslash escapes, so a path literally containing `\t` would become a tab and
# never match its own inventory row.
key_of() {
  P=$2 awk -v pl="$PREFIX_LEN" \
    'substr($0,pl+1)==ENVIRON["P"] { print substr($0,1,pl-1); exit }' "$1"
}
paths_of() { cut -c$((PREFIX_LEN + 1))- "$1"; }

# --- the comment-only rule for declared-divergent java pairs ----------------
#
# Comments, blank lines and leading/trailing whitespace removed, so two copies
# of a declared-divergent file can be compared for SUBSTANTIVE difference.
#
# The filter is deliberately simple and deliberately one-sided: it never tries
# to parse Java. A line containing a double quote is copied through from that
# quote onwards, comment markers and all, so nothing inside a string literal is
# ever deleted. That under-strips - a trailing `// note` on a line that also
# holds a string survives - and under-stripping can only make two copies look
# MORE different than they are. So the rule below errs toward accepting a pair
# as legitimately divergent and never toward hiding a real difference.
strip_comments() { # <infile> <outfile>
  awk '
    BEGIN { inblk = 0 }
    {
      line = $0; out = ""
      while (length(line) > 0) {
        if (inblk) {                       # inside /* ... */: drop until it closes
          i = index(line, "*/")
          if (i == 0) { line = "" } else { line = substr(line, i + 2); inblk = 0 }
          continue
        }
        if (index(line, "\"") > 0) {       # a string may start here: keep the rest verbatim
          out = out line; line = ""; continue
        }
        b = index(line, "/*"); l = index(line, "//")
        if (l > 0 && (b == 0 || l < b)) {  # line comment: drop it and the rest of the line
          out = out substr(line, 1, l - 1); line = ""; continue
        }
        if (b > 0) {                       # block comment opens here
          out = out substr(line, 1, b - 1); line = substr(line, b + 2); inblk = 1; continue
        }
        out = out line; line = ""
      }
      sub(/^[ \t]+/, "", out); sub(/[ \t]+$/, "", out)
      if (out != "") print out
    }
  ' "$1" > "$2"
}

# A declaration that records only differing commentary is worse than no
# declaration: it licenses the pair to drift for real, unnoticed, later.
check_comment_only() { # <label> <A name> <B name> <tree> <path> <A id> <B id>
  local label=$1 an=$2 bn=$3 tree=$4 p=$5 aid=$6 bid=$7 st
  git cat-file blob "$aid" > "$tmpd/bf" \
    || die "could not read the $an blob $aid for '$tree $p'"
  git cat-file blob "$bid" > "$tmpd/bl" \
    || die "could not read the $bn blob $bid for '$tree $p'"
  strip_comments "$tmpd/bf" "$tmpd/bfs" \
    || die "the comment filter failed on the $an copy of '$tree $p'"
  strip_comments "$tmpd/bl" "$tmpd/bls" \
    || die "the comment filter failed on the $bn copy of '$tree $p'"
  cmp -s "$tmpd/bfs" "$tmpd/bls"; st=$?
  case $st in
    0) fail "$label: 'divergent $tree $p' but the two copies differ only in comments or whitespace; make them identical and remove the entry." ;;
    1) ;;
    *) die "cmp failed on the stripped copies of '$tree $p'; refusing to pass the pair as legitimately divergent" ;;
  esac
}

# Inventories sort by mode+id, so extracted paths must be re-sorted before comm;
# otherwise comm silently under-counts and the summary understates how much was
# actually compared.
count_pairs() { # <A list> <B list>
  paths_of "$1" > "$tmpd/ca" || return 1
  LC_ALL=C sort "$tmpd/ca" > "$tmpd/ca.s" || return 1
  paths_of "$2" > "$tmpd/cb" || return 1
  LC_ALL=C sort "$tmpd/cb" > "$tmpd/cb.s" || return 1
  comm -12 "$tmpd/ca.s" "$tmpd/cb.s" > "$tmpd/cc" || return 1
  local n; n=$(wc -l < "$tmpd/cc") || return 1
  printf '%s' "${n//[[:space:]]/}"
}

# --- one pair ---------------------------------------------------------------

summaries=""
check_pair() { # <side a> <side b> <manifest>
  local a=$1 b=$2 m=$3 an bn label norm tree p ak bk side line kind rest want
  local pairs tpairs rpairs decl
  an=$(side_name "$a"); bn=$(side_name "$b"); label="$a/$b"
  norm="$tmpd/norm_${a}_$b"

  declared_divergent() { grep -Fxq "divergent|$1|$2" "$norm"; }
  declared_side() {
    grep -Fxq "one-sided|$1|$a|$2" "$norm" && { echo "$a"; return; }
    grep -Fxq "one-sided|$1|$b|$2" "$norm" && { echo "$b"; return; }
    echo ""
  }

  # pass A: everything in either index must be declared or identical
  for tree in main test res; do
    { paths_of "$(inv "$a" "$tree")"; paths_of "$(inv "$b" "$tree")"; } > "$tmp1" \
      || die "could not build the $label $tree path list"
    LC_ALL=C sort -u "$tmp1" > "$union" || die "sort failed while building the $label $tree path union"
    while IFS= read -r p; do
      [ -n "$p" ] || continue
      ak=$(key_of "$(inv "$a" "$tree")" "$p")
      bk=$(key_of "$(inv "$b" "$tree")" "$p")
      side=$(declared_side "$tree" "$p")

      if [ -n "$ak" ] && [ -n "$bk" ]; then
        if [ "$ak" != "$bk" ] && ! declared_divergent "$tree" "$p"; then
          fail "$label $tree: $p differs between trees but is not declared. Add 'divergent $tree $p' to $m if intended."
        fi
      elif [ -n "$ak" ]; then
        [ -n "$side" ] || fail "$label $tree: $p exists only in the $an tree and is not declared. Add 'one-sided $tree $a $p' to $m, or restore the $bn copy."
      elif [ -n "$bk" ]; then
        [ -n "$side" ] || fail "$label $tree: $p exists only in the $bn tree and is not declared. Add 'one-sided $tree $b $p' to $m, or restore the $an copy."
      fi
    done < "$union"
  done

  # pass B: every declared entry must still describe reality
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    kind=${line%%|*}; rest=${line#*|}
    tree=${rest%%|*}; rest=${rest#*|}

    case "$tree" in
      main|test|res) ;;
      *) fail "manifest $m: unknown tree '$tree' in: $line"; continue ;;
    esac

    if [ "$kind" = divergent ]; then
      p=$rest
      ak=$(key_of "$(inv "$a" "$tree")" "$p")
      bk=$(key_of "$(inv "$b" "$tree")" "$p")
      if [ -z "$ak" ] || [ -z "$bk" ]; then
        fail "manifest $m: 'divergent $tree $p' but the file is missing from $([ -z "$ak" ] && echo "$an" || echo "$bn"). Remove the entry, or declare it one-sided."
      elif [ "$ak" = "$bk" ]; then
        fail "manifest $m: 'divergent $tree $p' but the two copies are now identical. Remove the stale entry."
      else
        # The keys differ, so the bytes differ. Java sources get the stronger
        # test: differing only in commentary is not a legitimate divergence.
        case "$tree" in
          main|test)
            case "$p" in
              *.java) check_comment_only "manifest $m" "$an" "$bn" "$tree" "$p" "${ak#* }" "${bk#* }" ;;
            esac
            ;;
        esac
      fi
    else
      want=${rest%%|*}; p=${rest#*|}
      if [ "$want" != "$a" ] && [ "$want" != "$b" ]; then
        fail "manifest $m: unknown side '$want' in: $line"; continue
      fi
      ak=$(key_of "$(inv "$a" "$tree")" "$p")
      bk=$(key_of "$(inv "$b" "$tree")" "$p")
      if [ -n "$ak" ] && [ -n "$bk" ]; then
        fail "manifest $m: 'one-sided $tree $want $p' but it now exists in BOTH trees. Remove the stale entry, or delete the unintended copy."
      elif [ -z "$ak" ] && [ -z "$bk" ]; then
        fail "manifest $m: 'one-sided $tree $want $p' but the file exists in NEITHER tree. Remove the stale entry."
      elif [ "$want" = "$a" ] && [ -z "$ak" ]; then
        fail "manifest $m: 'one-sided $tree $a $p' but the file is in the $bn tree instead. It changed sides."
      elif [ "$want" = "$b" ] && [ -z "$bk" ]; then
        fail "manifest $m: 'one-sided $tree $b $p' but the file is in the $an tree instead. It changed sides."
      fi
    fi
  done < "$norm"

  pairs=$(count_pairs "$(inv "$a" main)" "$(inv "$b" main)") || die "could not count $label main pairs"
  tpairs=$(count_pairs "$(inv "$a" test)" "$(inv "$b" test)") || die "could not count $label test pairs"
  rpairs=$(count_pairs "$(inv "$a" res)" "$(inv "$b" res)")   || die "could not count $label res pairs"
  decl=$(grep -c . "$norm")
  summaries="$summaries  $label: $pairs mirrored main pairs, $tpairs mirrored test pairs, $rpairs mirrored res pairs, $decl declared exceptions ($m)
"
}

for spec in $PAIRS; do
  a=${spec%%:*}; rest=${spec#*:}; b=${rest%%:*}; m=${rest#*:}
  check_pair "$a" "$b" "$m"
done

# --- Forge port base guard --------------------------------------------------
#
# tools/forge-port-base.txt: one "<blob id> <path>" line per file under src/main
# and src/test, path repo-relative and taken verbatim after the first space.
# Only lines STARTING with `#` are comments, since a path may contain `#`.

base_msg="port this change to ornithe/, then update its line in $port_base"
if [ -f "$port_base" ]; then
  awk '!/^#/ && NF' "$port_base" > "$tmpd/brec" || die "could not read $port_base"
  if ! awk -v n="$HEXLEN" '
         { id = substr($0, 1, n)
           if (length($0) < n + 2 || id !~ /^[0-9a-f]+$/ || substr($0, n + 1, 1) != " ") { print "  " $0; bad = 1 } }
         END { exit bad ? 1 : 0 }' "$tmpd/brec" > "$tmp1"; then
    echo "error: malformed lines in $port_base (want '<blob id> <path>'):" >&2
    head -5 "$tmp1" >&2
    exit 2
  fi
  { cut -c8- "$tmpd/base_main" && cut -c8- "$tmpd/base_test"; } > "$tmpd/bidx" \
    || die "could not build the Forge index list for the port base guard"
  awk -v n="$HEXLEN" '
    FNR == NR { p = substr($0, n + 2)
                if (p in rec) { print "DUP|" p; next }
                rec[p] = substr($0, 1, n); next }
    { p = substr($0, n + 2); seen[p] = 1
      if (!(p in rec)) print "ADDED|" p
      else if (rec[p] != substr($0, 1, n)) print "CHANGED|" p }
    END { for (p in rec) if (!(p in seen)) print "REMOVED|" p }
  ' "$tmpd/brec" "$tmpd/bidx" > "$tmp1" || die "the port base comparison failed"
  LC_ALL=C sort "$tmp1" > "$tmpd/bres" || die "sort failed on the port base comparison"
  if grep -q '^DUP|' "$tmpd/bres"; then
    echo "error: duplicate paths in $port_base:" >&2
    sed -n 's/^DUP|/  /p' "$tmpd/bres" >&2
    exit 2
  fi
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    kind=${line%%|*}; p=${line#*|}
    case "$kind" in
      CHANGED) fail "forge port base: $p changed since the port base; $base_msg" ;;
      ADDED)   fail "forge port base: $p was added to the Forge tree; $base_msg" ;;
      REMOVED) fail "forge port base: $p was removed from the Forge tree; $base_msg" ;;
      *)       die "internal: unexpected port base result '$line'" ;;
    esac
  done < "$tmpd/bres"
  nbase=$(grep -c . "$tmpd/brec")
  summaries="$summaries  forge port base: $nbase Forge files checked against $port_base
"
else
  summaries="$summaries  forge port base: off (no $port_base)
"
fi

# --- report -----------------------------------------------------------------

for p in $skipped; do
  summaries="$summaries  $p: skipped (no Ornithe tree at ornithe/src)
"
done

# This check reads the index, so a purely local edit is invisible to it. In CI
# that is a distinction without a difference. Run by hand it would otherwise look
# reassuring while comparing the previous content, so say so - and if the probe
# itself fails, say that rather than implying there were no local edits.
if git diff --name-only -- $TREES $BASE_ROOTS > "$tmp1" 2>/dev/null; then
  unstaged=$(grep -c . "$tmp1")
  if [ "$unstaged" != "0" ]; then
    echo "  note: $unstaged compared file(s) have unstaged edits. This check reads the git index,"
    echo "        so those edits were NOT compared. Stage them and re-run to include them."
  fi
else
  echo "  warning: could not determine whether there are unstaged edits (git diff failed);"
  echo "           this run reflects the index only."
fi

npairs=0; for spec in $PAIRS; do npairs=$((npairs + 1)); done
if [ "$failures" -eq 0 ]; then
  printf '%s' "$summaries"
  echo "tree drift check: OK ($npairs tree pair(s) checked)"
  echo "  scope: git index contents - an untracked or unstaged change is not compared until it is staged."
  exit 0
fi

echo
echo "tree drift check: $failures problem(s). Each line names the manifest or base file to fix."
exit 1
