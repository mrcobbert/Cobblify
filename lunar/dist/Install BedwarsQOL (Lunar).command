#!/bin/bash
# Cobblify — one-click installer for Lunar Client (macOS).
# Double-click this file. It copies the Weave loader + the mod into place and
# creates a double-click launcher — no Lunar settings to touch.

DIR="$(cd "$(dirname "$0")" && pwd)"

echo ""
echo "  Installing Cobblify for Lunar Client..."

shopt -s nullglob
agents=("$DIR"/Weave-Loader-Agent-*.jar)
mods=("$DIR"/Cobblify-Lunar-*.jar)
shopt -u nullglob

if [ "${#agents[@]}" -ne 1 ] || [ "${#mods[@]}" -ne 1 ]; then
  echo "  ❌ Expected exactly one Weave-Loader-Agent-*.jar and exactly one"
  echo "     Cobblify-Lunar-*.jar next to this installer"
  echo "     (found ${#agents[@]} agent jar(s) and ${#mods[@]} mod jar(s))."
  echo "     Extract the bundle into a fresh, empty folder and run this again."
  echo ""
  read -p "  Press Return to close."
  exit 1
fi

AGENT="$(basename "${agents[0]}")"
MOD="$(basename "${mods[0]}")"

mkdir -p "$HOME/.weave/mods"
cp -f "${agents[0]}" "$HOME/.weave/$AGENT"
cp -f "${mods[0]}"   "$HOME/.weave/mods/$MOD"

LAUNCHER="Launch Lunar (Cobblify).command"

write_launcher() {
  cat > "$1" <<LAUNCHEOF
#!/bin/bash
# Launch the official Lunar Client with Cobblify (Weave) injected.
# Fully quit Lunar first (Cmd+Q), then double-click this file.

AGENT="\$HOME/.weave/$AGENT"
LUNAR="/Applications/Lunar Client.app/Contents/MacOS/Lunar Client"

if [ ! -f "\$AGENT" ]; then
  echo "❌ Weave agent not found at: \$AGENT"
  echo "   Run the Cobblify installer again."
  read -p "Press Return to close."
  exit 1
fi
if [ ! -x "\$LUNAR" ]; then
  echo "❌ Lunar Client not found at: \$LUNAR"
  echo "   Install Lunar Client first, then run this again."
  read -p "Press Return to close."
  exit 1
fi

echo "If Lunar is already open, fully quit it (Cmd+Q) first, then run this again."
export JAVA_TOOL_OPTIONS="-javaagent:\$HOME/.weave/$AGENT"
exec "\$LUNAR" "\$@"
LAUNCHEOF
  chmod +x "$1"
}

write_launcher "$DIR/$LAUNCHER"
write_launcher "$HOME/Desktop/$LAUNCHER"

echo "  ✅ Done."
echo ""
echo "  ────────────────────────────────────────────────────────────"
echo "  A launcher named '$LAUNCHER' was placed"
echo "  next to this installer AND on your Desktop."
echo "  Use it every time you play — it starts Lunar with Cobblify loaded."
echo ""
echo "  Remaining steps (in Lunar, after launching):"
echo ""
echo "   1. Log into Lunar Client."
echo "   2. Pick version 1.8.9 and click Play."
echo "   3. Turn Waypoints OFF inside your active Lunar settings profile."
echo "   4. Press Right Shift in-game to open the Cobblify settings menu."
echo "  ────────────────────────────────────────────────────────────"
echo ""
read -p "  Press Return to close."
