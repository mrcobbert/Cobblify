#!/usr/bin/env bash
#
# Self-test for tools/check-tree-drift.sh.
#
# The drift check is a safety net, and a safety net that passes when it should
# fail is worse than none - it manufactures confidence. So each behaviour it
# claims is asserted here by perturbing a throwaway clone and requiring the
# expected exit code and message.
#
# Two rules keep this from rotting, both learned the hard way:
#
#   * Every case runs in a disposable clone. An earlier version perturbed the
#     real working tree and restored it afterwards; when one of its hardcoded
#     fixtures was later moved into common/, the restore silently left a stray
#     file behind in the repository.
#   * Fixtures are derived from the tree at runtime, never hardcoded. The same
#     move broke the old harness because it named a specific file that no longer
#     lived where it expected.
#
# Usage: tools/test-tree-drift.sh
# Exit:  0 = every case behaved as specified, 1 = at least one did not.

set -uo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo" || exit 1

CHECK=tools/check-tree-drift.sh
MANIFEST=tools/tree-divergence.txt
pass=0; fail=0

listing() { git ls-files "$1" | sed "s|^$1/||" | LC_ALL=C sort; }

# --- derive fixtures from the current tree ----------------------------------

# A live mirrored pair that is byte-identical and NOT declared: perturbing it
# must be reported as undeclared drift.
MIRROR=$(comm -12 <(listing src/main/java) <(listing lunar/src/main/java) | while read -r f; do
  cmp -s "src/main/java/$f" "lunar/src/main/java/$f" || continue
  grep -Fq " $f" "$MANIFEST" && continue
  echo "$f"; break
done)
# A declared-divergent pair, and a declared Forge-only file.
DIVERGENT=$(awk '$1=="divergent" && $2=="main" { print $3; exit }' "$MANIFEST")
ONESIDED=$(awk '$1=="one-sided" && $2=="main" && $3=="forge" { print $4; exit }' "$MANIFEST")

# The same two shapes under the mirrored resource trees, which are compared too.
RES_MIRROR=$(comm -12 <(listing src/main/resources) <(listing lunar/src/main/resources) | while read -r f; do
  cmp -s "src/main/resources/$f" "lunar/src/main/resources/$f" || continue
  grep -Fq " $f" "$MANIFEST" && continue
  echo "$f"; break
done)
# Derived from the tree rather than from the manifest: an undeclared one-sided
# resource would already fail the baseline case, so whatever this finds is the
# declared one - and the fixture exists before the declaration does.
RES_ONESIDED=$(comm -23 <(listing src/main/resources) <(listing lunar/src/main/resources) | head -1)

for v in MIRROR DIVERGENT ONESIDED RES_MIRROR RES_ONESIDED; do
  eval "val=\$$v"
  [ -n "$val" ] || { echo "cannot derive fixture $v from the current tree"; exit 1; }
done
echo "fixtures: mirror=$MIRROR divergent=$DIVERGENT one-sided=$ONESIDED"
echo "          res-mirror=$RES_MIRROR res-one-sided=$RES_ONESIDED"
echo

# --- harness ----------------------------------------------------------------

clone() {
  local d; d=$(mktemp -d) || return 1
  git clone -q --depth 1 "file://$repo" "$d/c" 2>/dev/null || { rm -rf "$d"; return 1; }
  # The checker AND the manifest come from the working tree, not from the cloned
  # commit: the two evolve together, so a clone that kept its committed manifest
  # would judge a new checker rule against the old declarations, and a manifest
  # edit could not be tested until after it had been committed.
  #
  # Everything else in the clone is HEAD. A case that depends on an uncommitted
  # source edit therefore stays red until that edit is committed - which is the
  # point: the checker and its manifest are under test, the tree is the subject.
  cp "$repo/$CHECK" "$d/c/$CHECK" && chmod +x "$d/c/$CHECK" \
    && cp "$repo/$MANIFEST" "$d/c/$MANIFEST" || { rm -rf "$d"; return 1; }
  echo "$d"
}

run() { # <label> <expected exit> <expected message substring>; setup on stdin
  local label=$1 want=$2 msg=$3 d c out rc setup
  setup=$(cat)
  d=$(clone) || { printf '  [BAD ] %-44s clone failed\n' "$label"; fail=$((fail+1)); return; }
  c="$d/c"
  ( cd "$c" && eval "$setup" ) >/dev/null 2>&1
  out=$(cd "$c" && ./$CHECK 2>&1); rc=$?
  if [ "$rc" -ne "$want" ]; then
    printf '  [BAD ] %-44s expected exit %s, got %s\n' "$label" "$want" "$rc"
    printf '%s\n' "$out" | head -3 | sed 's/^/           /'; fail=$((fail+1))
  elif [ -n "$msg" ] && ! printf '%s' "$out" | grep -qF "$msg"; then
    printf '  [BAD ] %-44s missing: %s\n' "$label" "$msg"
    printf '%s\n' "$out" | head -3 | sed 's/^/           /'; fail=$((fail+1))
  else
    printf '  [ OK ] %-44s exit=%s\n' "$label" "$rc"; pass=$((pass+1))
  fi
  rm -rf "$d"
}

shimmed() { # <label> <expected exit> <msg> <tool> <shim body>; no stdin setup
  local label=$1 want=$2 msg=$3 tool=$4 body=$5 d c out rc
  d=$(clone) || { printf '  [BAD ] %-44s clone failed\n' "$label"; fail=$((fail+1)); return; }
  c="$d/c"; mkdir -p "$c/.shim"
  printf '%s\n' "$body" > "$c/.shim/$tool"; chmod +x "$c/.shim/$tool"
  out=$(cd "$c" && PATH="$c/.shim:$PATH" ./$CHECK 2>&1); rc=$?
  if [ "$rc" -eq "$want" ] && printf '%s' "$out" | grep -qF "$msg"; then
    printf '  [ OK ] %-44s exit=%s\n' "$label" "$rc"; pass=$((pass+1))
  else
    printf '  [BAD ] %-44s expected exit %s, got %s\n' "$label" "$want" "$rc"
    printf '%s\n' "$out" | head -2 | sed 's/^/           /'; fail=$((fail+1))
  fi
  rm -rf "$d"
}

# --- baseline ---------------------------------------------------------------

echo "baseline:"
run "clean tree passes" 0 "tree drift check: OK" <<'CMD'
true
CMD

# --- the six manifest rules -------------------------------------------------

echo "manifest rules:"

run "undeclared byte difference" 1 "differs between trees but is not declared" <<CMD
printf '\n' >> "lunar/src/main/java/$MIRROR"
git add -- "lunar/src/main/java/$MIRROR"
CMD

run "one copy of a mirrored file deleted" 1 "exists only in the Forge tree" <<CMD
git rm -q -- "lunar/src/main/java/$MIRROR"
CMD

run "declared-divergent pair now identical" 1 "the two copies are now identical" <<CMD
cp "src/main/java/$DIVERGENT" "lunar/src/main/java/$DIVERGENT"
git add -- "lunar/src/main/java/$DIVERGENT"
CMD

# A declaration that only records differing commentary is worse than no
# declaration: it licenses the two copies to drift for real later.
run "comment-only divergence is reported" 1 "differ only in comments or whitespace" <<CMD
{ cat "src/main/java/$DIVERGENT"; printf '// comment only\n'; } > "lunar/src/main/java/$DIVERGENT"
git add -- "lunar/src/main/java/$DIVERGENT"
CMD

run "one-sided file now in both trees" 1 "exists in BOTH trees" <<CMD
mkdir -p "lunar/src/main/java/\$(dirname "$ONESIDED")"
cp "src/main/java/$ONESIDED" "lunar/src/main/java/$ONESIDED"
git add -- "lunar/src/main/java/$ONESIDED"
CMD

run "one-sided file changed sides" 1 "is in the Lunar tree instead" <<CMD
mkdir -p "lunar/src/main/java/\$(dirname "$ONESIDED")"
cp "src/main/java/$ONESIDED" "lunar/src/main/java/$ONESIDED"
git add -- "lunar/src/main/java/$ONESIDED"
git rm -q -- "src/main/java/$ONESIDED"
CMD

run "one-sided file deleted from both" 1 "exists in NEITHER tree" <<CMD
git rm -q -- "src/main/java/$ONESIDED"
CMD

# --- the resource trees -----------------------------------------------------
#
# src/main/resources and lunar/src/main/resources are mirrored too: the jars
# ship the same fonts, icons and CA bundle, and a one-sided asset edit is as
# real a divergence as a one-sided source edit.

echo "resource trees:"

run "undeclared resource edit is drift" 1 "differs between trees but is not declared" <<CMD
printf '\n' >> "lunar/src/main/resources/$RES_MIRROR"
git add -- "lunar/src/main/resources/$RES_MIRROR"
CMD

run "one-sided resource now in both trees" 1 "exists in BOTH trees" <<CMD
mkdir -p "lunar/src/main/resources/\$(dirname "$RES_ONESIDED")"
cp "src/main/resources/$RES_ONESIDED" "lunar/src/main/resources/$RES_ONESIDED"
git add -- "lunar/src/main/resources/$RES_ONESIDED"
CMD

# --- pathnames --------------------------------------------------------------

echo "pathnames:"

run "non-ASCII path divergence is seen" 1 "differs between trees but is not declared" <<CMD
cp "src/main/java/$DIVERGENT"       "src/main/java/com/bedwarsqol/Café.java"
cp "lunar/src/main/java/$DIVERGENT" "lunar/src/main/java/com/bedwarsqol/Café.java"
git add -- "src/main/java/com/bedwarsqol/Café.java" "lunar/src/main/java/com/bedwarsqol/Café.java"
CMD

run "literal-backslash path is not escaped" 1 "differs between trees but is not declared" <<CMD
cp "src/main/java/$DIVERGENT"       'src/main/java/com/bedwarsqol/Back\tName.java'
cp "lunar/src/main/java/$DIVERGENT" 'lunar/src/main/java/com/bedwarsqol/Back\tName.java'
git add -- 'src/main/java/com/bedwarsqol/Back\tName.java' 'lunar/src/main/java/com/bedwarsqol/Back\tName.java'
CMD

run "tab in a path is accepted" 0 "tree drift check: OK" <<'CMD'
F=$(printf 'src/main/java/com/bedwarsqol/Tab\tName.java')
L=$(printf 'lunar/src/main/java/com/bedwarsqol/Tab\tName.java')
printf 'class X{}\n' > "$F"; printf 'class X{}\n' > "$L"
git add -- "$F" "$L"
CMD

run "newline in a path is refused" 2 "contains a newline" <<CMD
F=\$(printf 'src/main/java/com/bedwarsqol/Line\nBreak.java')
L=\$(printf 'lunar/src/main/java/com/bedwarsqol/Line\nBreak.java')
cp "src/main/java/$DIVERGENT" "\$F"; cp "lunar/src/main/java/$DIVERGENT" "\$L"
git add -- "\$F" "\$L"
CMD

# --- symlinks and modes -----------------------------------------------------

echo "symlinks and modes:"

run "differing symlinks are divergence" 1 "differs between trees but is not declared" <<CMD
ln -s Target.java   "src/main/java/com/bedwarsqol/Link.java"
ln -s ./Target.java "lunar/src/main/java/com/bedwarsqol/Link.java"
git add -- "src/main/java/com/bedwarsqol/Link.java" "lunar/src/main/java/com/bedwarsqol/Link.java"
CMD

run "symlinked parent cannot hide divergence" 1 "differs between trees but is not declared" <<CMD
mkdir -p src/main/java/com/bedwarsqol/probe lunar/src/main/java/com/bedwarsqol/probe
cp "src/main/java/$DIVERGENT"       src/main/java/com/bedwarsqol/probe/P.java
cp "lunar/src/main/java/$DIVERGENT" lunar/src/main/java/com/bedwarsqol/probe/P.java
git add src/main/java/com/bedwarsqol/probe lunar/src/main/java/com/bedwarsqol/probe
shared=\$(mktemp -d); cp src/main/java/com/bedwarsqol/probe/P.java "\$shared/"
rm -rf src/main/java/com/bedwarsqol/probe lunar/src/main/java/com/bedwarsqol/probe
ln -s "\$shared" src/main/java/com/bedwarsqol/probe
ln -s "\$shared" lunar/src/main/java/com/bedwarsqol/probe
CMD

run "symlink vs regular file sharing an object" 1 "differs between trees but is not declared" <<'CMD'
ln -s same-target src/main/java/com/bedwarsqol/Mode.java
printf 'same-target' > lunar/src/main/java/com/bedwarsqol/Mode.java
git add -- src/main/java/com/bedwarsqol/Mode.java lunar/src/main/java/com/bedwarsqol/Mode.java
CMD

# --- premise guards ---------------------------------------------------------

echo "premise guards:"

run "tracked .gitattributes filter refused" 2 "content filters or EOL attributes apply" <<'CMD'
printf '*.java text=auto\n' > .gitattributes
git add .gitattributes
CMD

run ".git/info/attributes filter refused" 2 "content filters or EOL attributes apply" <<'CMD'
printf '*.java filter=twist\n' > .git/info/attributes
git config filter.twist.clean cat
CMD

run "core.attributesFile filter refused" 2 "content filters or EOL attributes apply" <<'CMD'
printf '*.java filter=twist\n' > "$PWD/ga"
git config core.attributesFile "$PWD/ga"
git config filter.twist.clean cat
CMD

run "irrelevant attribute does not over-refuse" 0 "tree drift check: OK" <<'CMD'
printf '*.md linguist-language=Java\n' > .gitattributes
git add .gitattributes
CMD

run "core.autocrlf refused" 2 "rewrites bytes between index and worktree" <<'CMD'
git config core.autocrlf true
CMD

run "unmerged index refused" 2 "unmerged entries" <<CMD
p="src/main/java/$DIVERGENT"
a=\$(printf 'a\n' | git hash-object -w --stdin)
b=\$(printf 'b\n' | git hash-object -w --stdin)
git rm -q --cached -- "\$p"
printf '100644 %s 2\t%s\n100644 %s 3\t%s\n' "\$a" "\$p" "\$b" "\$p" | git update-index --index-info
CMD

run "emptied Forge index is drift, not infra" 1 "exists only in the Lunar tree" <<'CMD'
git rm --cached -r -q src/main/java
CMD

# --- tool failures must never read as "clean" -------------------------------

echo "tool failures fail closed:"

shimmed "git failure refuses a clean report" 2 "error:" git \
'#!/bin/sh
exit 1'

shimmed "sed failure refuses a clean report" 2 "error:" sed \
'#!/bin/sh
exit 1'

shimmed "git config failure refuses" 2 "could not read git config" git \
'#!/bin/sh
[ "$1" = config ] && exit 75
exec /usr/bin/git "$@"'

# --- summary truthfulness ---------------------------------------------------

echo "summary:"
d=$(clone)
if [ -n "$d" ]; then
  c="$d/c"
  out=$(cd "$c" && ./$CHECK 2>&1)
  want=$(cd "$c" && comm -12 <(git ls-files src/main/java | sed 's|^src/main/java/||' | LC_ALL=C sort) \
                             <(git ls-files lunar/src/main/java | sed 's|^lunar/src/main/java/||' | LC_ALL=C sort) | wc -l | tr -d ' ')
  got=$(printf '%s' "$out" | sed -n 's/.*OK (\([0-9]*\) mirrored main pairs.*/\1/p')
  if [ "$want" = "$got" ]; then
    printf '  [ OK ] %-44s %s pairs\n' "reported pair count is truthful" "$got"; pass=$((pass+1))
  else
    printf '  [BAD ] %-44s reported %s, actual %s\n' "reported pair count is truthful" "$got" "$want"; fail=$((fail+1))
  fi
  rwant=$(cd "$c" && comm -12 <(git ls-files src/main/resources | sed 's|^src/main/resources/||' | LC_ALL=C sort) \
                              <(git ls-files lunar/src/main/resources | sed 's|^lunar/src/main/resources/||' | LC_ALL=C sort) | wc -l | tr -d ' ')
  rgot=$(printf '%s' "$out" | sed -n 's/.*, \([0-9][0-9]*\) mirrored res pairs.*/\1/p')
  if [ "$rwant" = "$rgot" ]; then
    printf '  [ OK ] %-44s %s pairs\n' "reported res pair count is truthful" "$rgot"; pass=$((pass+1))
  else
    printf '  [BAD ] %-44s reported %s, actual %s\n' "reported res pair count is truthful" "$rgot" "$rwant"; fail=$((fail+1))
  fi
  rm -rf "$d"
fi

run "unstaged edit is disclosed" 0 "have unstaged edits" <<CMD
printf '\n' >> "lunar/src/main/java/$MIRROR"
CMD

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
