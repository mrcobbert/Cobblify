#!/usr/bin/env bash
#
# Cobblify Launcher - build a REAL, runnable launcher for hands-on testing.
#
# This is the Phase 3 manual gate. It is NOT the owner packaging path: it runs no
# Gradle build and touches no backend token. It injects the jars ALREADY
# INSTALLED on this machine (~/.weave) into the blank launcher build, signs it,
# and drops it on the Desktop ready to open.
#
# THIS SCRIPT ITSELF WRITES NOTHING to ~/.weave or ~/.lunarclient. Only the
# launcher you then open does that, and only when Lunar is closed.
#
# Usage:  double-click this file, or run it in a terminal.

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
die() { echo "error: $*" >&2; read -r -p "Press Return to close." _ 2>/dev/null || true; exit 1; }

. "$repo_root/launcher/tools/app-inject-lib.sh"

echo ""
echo "  Cobblify Launcher - test drive"
echo "  ──────────────────────────────"

# --- 1. Lunar must be closed -------------------------------------------------
#
# The launcher refuses to edit launcher.json while Lunar runs (Lunar rewrites
# that file on exit and would clobber the edit). Catch it here so the failure is
# explained in plain terms rather than as a blocked state in the UI.
if pgrep -f "^/Applications/Lunar Client.app/Contents/MacOS/Lunar Client$" >/dev/null 2>&1; then
  die "Lunar Client is running. Quit it fully (Cmd+Q), then run this again."
fi

# --- 2. the blank build must exist -------------------------------------------
app_src="$repo_root/$COBBLIFY_APP_BUILD_DIR/$COBBLIFY_APP_NAME"
if [ ! -d "$app_src" ]; then
  echo "  No launcher build found. Building it now (a few minutes)..."
  ( cd "$repo_root/launcher" && npm run tauri build -- --bundles app ) \
    || die "the launcher build failed"
fi
cobblify_assert_blank_app "$app_src"

# --- 3. locate the jars already installed on this machine --------------------
agent_src=$(find "$HOME/.weave" -maxdepth 1 -name 'Weave-Loader-Agent-*.jar' | head -1)
mod_src=$(find "$HOME/.weave/mods" -maxdepth 1 -name 'Cobblify-Lunar-*.jar' | head -1)
[ -n "$agent_src" ] || die "no Weave agent found in ~/.weave"
[ -n "$mod_src" ]   || die "no Cobblify jar found in ~/.weave/mods"
version=$(basename "$mod_src" | sed -n 's/^Cobblify-Lunar-\(.*\)\.jar$/\1/p')
[ -n "$version" ] || die "could not read a version out of $(basename "$mod_src")"

echo "  agent:   $(basename "$agent_src")"
echo "  mod:     $(basename "$mod_src")  (v$version)"

# --- 4. stage on the Desktop, inject, sign -----------------------------------
#
# Deliberately NOT /tmp. Tauri refuses to resolve its resources when the
# executable has a symlinked ancestor on macOS, and /tmp -> /private/tmp, so a
# bundle run from there dies with "Cannot locate the bundled resources".
dest="$HOME/Desktop/$COBBLIFY_APP_NAME"
rm -rf "$dest"
cp -R "$app_src" "$dest"
cobblify_inject_app "$dest" "$mod_src" "$agent_src" "$version"
cobblify_sign_app "$dest"

echo ""
echo "  Built: $dest"
echo ""
echo "  ────────────────────────────────────────────────────────────"
echo "  Before you open it, note what it SHOULD do:"
echo ""
echo "   1. Say \"Cobblify v$version ready\"."
echo "   2. Create ~/.lunarclient/settings/launcher.json.bak-cobblify"
echo "      (your pristine backup - it does not exist yet)."
echo "   3. Leave exactly ONE -javaagent entry per key in launcher.json."
echo "      You already have one from June, so it should replace, not add."
echo ""
echo "  Then check it worked:"
echo "     launcher/tools/check-state.sh"
echo "  ────────────────────────────────────────────────────────────"
echo ""
read -r -p "  Press Return to close." _ 2>/dev/null || true