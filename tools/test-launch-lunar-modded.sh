#!/usr/bin/env bash
#
# Self-test for lunar/launch-lunar-modded.command's agent injection (N2).
#
# JAVA_TOOL_OPTIONS is tokenized by the JVM on whitespace, so the -javaagent
# path has to carry embedded double quotes or a home directory containing a
# space (/Users/Jane Doe) is split into two options and the Weave agent never
# loads. The shipped installer's launcher has always written the quoted form;
# this dev launcher did not, which is the bug under test.
#
# What is asserted:
#   * with a HOME containing a space, the value handed to the Lunar binary is
#     exactly -javaagent:"<HOME>/.weave/Weave-Loader-Agent-1.3.3.jar", quotes
#     included - checked by reading it back out of a stub that stands in for
#     Lunar Client and prints what it was given;
#   * with no agent jar present, the script still refuses with "Weave agent not
#     found" and exit 1 rather than launching anything.
#
# The stub is selected with COBBLIFY_LUNAR_BIN, which exists so this test can
# run without a Lunar install - and so that a test run can never launch the real
# Lunar Client sitting in /Applications on a developer's Mac.
#
# Usage: tools/test-launch-lunar-modded.sh
# Exit:  0 = every case behaved as specified, 1 = at least one did not.

set -uo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo" || exit 1

SCRIPT=lunar/launch-lunar-modded.command
AGENT_JAR="Weave-Loader-Agent-1.3.3.jar"
pass=0; fail=0

tmp=$(mktemp -d) || exit 1
trap 'rm -rf "$tmp"' EXIT

ok()  { printf '  [ OK ] %-46s %s\n' "$1" "${2:-}"; pass=$((pass+1)); }
bad() { printf '  [BAD ] %-46s %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

[ -f "$SCRIPT" ] || { echo "missing $SCRIPT"; exit 1; }

# A stand-in for the Lunar binary, in a directory whose name has a space too:
# it prints the environment the launcher handed it and exits.
stub_dir="$tmp/lunar stub"
mkdir -p "$stub_dir" || exit 1
STUB="$stub_dir/Lunar Client"
cat > "$STUB" <<'STUBEOF'
#!/bin/bash
printf '%s\n' "$JAVA_TOOL_OPTIONS"
STUBEOF
chmod +x "$STUB" || exit 1

# --- case 1: a home directory containing a space ----------------------------

echo "agent path with a space in HOME:"
home="$tmp/home with space"
mkdir -p "$home/.weave" || exit 1
: > "$home/.weave/$AGENT_JAR" || exit 1

out=$(HOME="$home" COBBLIFY_LUNAR_BIN="$STUB" bash "$repo/$SCRIPT" 2>&1); rc=$?
got=$(printf '%s\n' "$out" | tail -1)
want="-javaagent:\"$home/.weave/$AGENT_JAR\""

if [ "$rc" -ne 0 ]; then
  bad "launcher reaches the Lunar binary" "exit=$rc"
  printf '%s\n' "$out" | head -3 | sed 's/^/           /'
else
  ok "launcher reaches the Lunar binary" "exit=$rc"
fi

if [ "$got" = "$want" ]; then
  ok "JAVA_TOOL_OPTIONS quotes the agent path" "$got"
else
  bad "JAVA_TOOL_OPTIONS quotes the agent path" "the JVM would split this on the space"
  printf '           want: %s\n           got:  %s\n' "$want" "$got"
fi

# --- case 2: no agent installed ---------------------------------------------

echo "no Weave agent installed:"
empty="$tmp/empty home"
mkdir -p "$empty" || exit 1

out2=$(HOME="$empty" COBBLIFY_LUNAR_BIN="$STUB" bash "$repo/$SCRIPT" 2>&1); rc2=$?
if [ "$rc2" -eq 1 ] && printf '%s' "$out2" | grep -qF "Weave agent not found"; then
  ok "refuses with the agent-not-found message" "exit=$rc2"
else
  bad "refuses with the agent-not-found message" "exit=$rc2 (expected 1)"
  printf '%s\n' "$out2" | head -3 | sed 's/^/           /'
fi

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
