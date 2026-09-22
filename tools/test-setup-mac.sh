#!/usr/bin/env bash
#
# Self-test for the Cloudflare login step of installers/setup-mac.command (R4).
#
# This test is static on purpose. The installer downloads a Node toolchain and
# the Worker source, then opens a real OAuth browser flow and deploys to the
# user's Cloudflare account - none of which can run unattended here. What broke
# was the *shape* of the login step, and shape is checkable without running it.
#
# The bug: an unconditional `npx wrangler login` sat in front of the block that
# probes `npx wrangler whoami` and only then logs in. Every run opened the OAuth
# flow, and because that bare call is outside any `||`, cancelling it exited
# non-zero straight into the ERR trap - so the user saw the generic "unexpected
# error on line N" instead of "Cloudflare login was cancelled".
#
# What is asserted:
#   * the installer is syntactically valid bash;
#   * `npx wrangler login` appears on exactly one line (the unconditional call
#     is gone);
#   * every line that invokes it carries `|| die "Cloudflare login was
#     cancelled"`, so a cancelled login can never reach the ERR trap;
#   * that call comes after the `npx wrangler whoami` probe, i.e. inside the
#     branch that only runs when the user is not already logged in.
#
# Usage: tools/test-setup-mac.sh
# Exit:  0 = every case behaved as specified, 1 = at least one did not.

set -uo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo" || exit 1

SCRIPT=installers/setup-mac.command
DIE_CLAUSE='|| die "Cloudflare login was cancelled"'
pass=0; fail=0

tmp=$(mktemp -d) || exit 1
trap 'rm -rf "$tmp"' EXIT

ok()  { printf '  [ OK ] %-46s %s\n' "$1" "${2:-}"; pass=$((pass+1)); }
bad() { printf '  [BAD ] %-46s %s\n' "$1" "${2:-}"; fail=$((fail+1)); }

[ -f "$SCRIPT" ] || { echo "missing $SCRIPT"; exit 1; }

echo "setup-mac.command login step:"

# --- 1. the installer still parses ------------------------------------------
if bash -n "$SCRIPT" 2>"$tmp/syntax.err"; then
  ok "installer parses" "bash -n"
else
  bad "installer parses" "$(head -1 "$tmp/syntax.err")"
fi

# --- 2. exactly one login call ----------------------------------------------
grep -n 'npx wrangler login' "$SCRIPT" > "$tmp/login.lines"
n=$(wc -l < "$tmp/login.lines" | tr -d ' ')
if [ "$n" = "1" ]; then
  ok "exactly one 'npx wrangler login'" "count=$n"
else
  bad "exactly one 'npx wrangler login'" "count=$n (expected 1)"
  sed 's/^/           /' "$tmp/login.lines"
fi

# --- 3. every login call handles a cancelled login itself --------------------
unguarded=$(grep -v -F "$DIE_CLAUSE" "$tmp/login.lines")
if [ -z "$unguarded" ]; then
  ok "login is guarded by the die message" "$(sed 's/:.*//' "$tmp/login.lines" | tr '\n' ' ')"
else
  bad "login is guarded by the die message" "line(s) without the || die:"
  printf '%s\n' "$unguarded" | sed 's/^/           /'
fi

# --- 4. the login sits after the logged-in probe -----------------------------
whoami_line=$(grep -n 'npx wrangler whoami' "$SCRIPT" | head -1 | cut -d: -f1)
login_line=$(head -1 "$tmp/login.lines" | cut -d: -f1)
if [ -n "$whoami_line" ] && [ -n "$login_line" ] && [ "$login_line" -gt "$whoami_line" ]; then
  ok "login follows the whoami probe" "whoami:$whoami_line login:$login_line"
else
  bad "login follows the whoami probe" "whoami:${whoami_line:-none} login:${login_line:-none}"
fi

echo
echo "passed=$pass failed=$fail"
[ "$fail" -eq 0 ]
