#!/usr/bin/env bash
#
# Self-test for the jar verdict in tools/bwqol-diag.command (R8).
#
# The diagnostic used to pin its "CURRENT" verdict to the sha256 of the 0.9.1
# jars, so every build after that one - including the build the user was told to
# install - came back as "UNKNOWN BUILD ... Replace with Jacob's exact jar". The
# report now identifies the jar by its own version (file name first, then the
# BedwarsQol.VERSION constant inside the jar) and keeps the marker-based
# stale/wrong-build verdicts, which are the ones that actually diagnose anything.
#
# What is asserted, each case in its own throwaway HOME:
#   1. a current-marker jar named Cobblify-Lunar-0.15.1.jar is reported with its
#      version, is not called an UNKNOWN BUILD in either the per-jar verdict or
#      the VERDICT summary, and the footer prints "Detected version: 0.15.1";
#   2. the same jar renamed Cobblify-Lunar.jar - no version in the name - is
#      still reported as 0.15.1, via the in-jar class constant;
#   3. a jar whose ChatNameTags class still has the rootTextField marker is
#      reported STALE, i.e. the marker verdicts are untouched by the above.
#
# The jars are built with zip from text files. bwqol-diag greps the class bytes
# with `grep -a`, so text stands in for compiled classes and no JDK is needed.
# BWQOL_OUT is always set: it redirects the report out of ~/Desktop (which does
# not exist in a temp HOME) and suppresses the final `open -R`, so running this
# test never opens a Finder window.
#
# Usage: tools/test-bwqol-diag.sh
# Exit:  0 = every case behaved as specified, 1 = at least one did not.

set -uo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo" || exit 1

DIAG=tools/bwqol-diag.command
pass=0; fail=0

tmp=$(mktemp -d) || exit 1
trap 'rm -rf "$tmp"' EXIT

ok()  { printf '  [ OK ] %-46s %s\n' "$1" "${2:-}"; pass=$((pass+1)); }
bad() { printf '  [BAD ] %-46s %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

[ -f "$DIAG" ] || { echo "missing $DIAG"; exit 1; }
command -v zip >/dev/null 2>&1 || { echo "zip is required"; exit 1; }

# make_jar <absolute dest .jar> <current|stale>
#   A minimal Weave-shaped mod jar: the manifest bwqol-diag looks for, the class
#   it greps for markers, and the class carrying the version constant.
make_jar() {
  local dest=$1 kind=$2 stage
  stage=$(mktemp -d "$tmp/stage.XXXXXX") || return 1
  mkdir -p "$stage/com/bedwarsqol/feature" || return 1
  printf '{"name":"Cobblify","entryPoints":["com.bedwarsqol.BedwarsQol"]}\n' \
    > "$stage/weave.mod.json"
  if [ "$kind" = stale ]; then
    # The pre-Jul-2-18:09 shape: rootTextField still present.
    printf 'aQ\003rootTextField\001chatLine\nDECIDE_WINDOW_MS\n' \
      > "$stage/com/bedwarsqol/feature/ChatNameTags.class"
  else
    printf 'aQ\003chatComponent\001DECIDE_WINDOW_MS\ndeferDecision\n' \
      > "$stage/com/bedwarsqol/feature/ChatNameTags.class"
  fi
  printf 'aQ\003VERSION\001Cobblify\n0.15.1\nbedwarsqol\n' \
    > "$stage/com/bedwarsqol/BedwarsQol.class"
  ( cd "$stage" && zip -q -r "$dest" . ) || return 1
  rm -rf "$stage"
}

# run_diag <case name> <jar basename> <current|stale> -> report path on stdout
run_diag() {
  local name=$1 jarname=$2 kind=$3 home
  home="$tmp/$name"
  mkdir -p "$home/.weave/mods" || return 1
  make_jar "$home/.weave/mods/$jarname" "$kind" || return 1
  HOME="$home" BWQOL_OUT="$home/report.txt" bash "$repo/$DIAG" >/dev/null 2>&1
  printf '%s' "$home/report.txt"
}

# has <label> <report> <grep -E pattern>   / hasnt <label> <report> <pattern>
has() {
  if grep -Eq "$3" "$2"; then ok "$1" "matched: $3"
  else bad "$1" "no line matching: $3"; grep -E 'verdict:|version:|^Mod jar:|Detected' "$2" 2>/dev/null | sed 's/^/           /'; fi
}
hasnt() {
  if grep -Eq "$3" "$2"; then
    bad "$1" "unexpected match: $3"
    grep -E "$3" "$2" | head -2 | sed 's/^/           /'
  else ok "$1" "absent: $3"; fi
}

# --- case 1: version in the file name ---------------------------------------

echo "current jar, version in the file name:"
r1=$(run_diag case1 "Cobblify-Lunar-0.15.1.jar" current) || { echo "setup failed"; exit 1; }
has   "per-jar version line"            "$r1" '^version: +0\.15\.1$'
has   "verdict names the version"       "$r1" '^verdict:.*0\.15\.1'
hasnt "verdict is not UNKNOWN BUILD"    "$r1" '^verdict:.*UNKNOWN BUILD'
hasnt "summary is not UNKNOWN BUILD"    "$r1" '^Mod jar:.*UNKNOWN BUILD'
has   "footer reports the version"      "$r1" '^Detected version: +0\.15\.1$'

# --- case 2: no version in the file name, constant fallback ------------------

echo "current jar, no version in the file name:"
r2=$(run_diag case2 "Cobblify-Lunar.jar" current) || { echo "setup failed"; exit 1; }
has   "version from the class constant" "$r2" '^version: +0\.15\.1$'
hasnt "verdict is not UNKNOWN BUILD"    "$r2" '^verdict:.*UNKNOWN BUILD'

# --- case 3: the marker verdicts still fire ---------------------------------

echo "jar with the stale rootTextField marker:"
r3=$(run_diag case3 "Cobblify-Lunar-0.15.1.jar" stale) || { echo "setup failed"; exit 1; }
has   "stale marker still wins"         "$r3" '^verdict: *STALE'
has   "summary carries the stale jar"   "$r3" '^Mod jar: *STALE'

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
