#!/bin/bash
# Launch the OFFICIAL Lunar Client with the Weave agent injected via JAVA_TOOL_OPTIONS.
# The Electron launcher inherits this env var and passes it to the game JVM it spawns,
# so the Weave loader (and our mod in ~/.weave/mods/) loads — without modifying Lunar,
# without lcqt, and without relying on Lunar's per-profile JVM-args field.
#
# Usage: fully quit Lunar first, then double-click this file (or run it in a terminal).

AGENT="$HOME/.weave/Weave-Loader-Agent-1.3.3.jar"
LUNAR="${COBBLIFY_LUNAR_BIN:-/Applications/Lunar Client.app/Contents/MacOS/Lunar Client}"

if [ ! -f "$AGENT" ]; then echo "Weave agent not found at: $AGENT"; exit 1; fi
if [ ! -x "$LUNAR" ]; then echo "Lunar Client not found at: $LUNAR"; exit 1; fi

# The quotes INSIDE the value are load-bearing: JAVA_TOOL_OPTIONS is tokenized by the
# JVM on whitespace, so a home path containing a space (/Users/Jane Doe) must be wrapped
# in embedded double quotes for the JVM to parse it as one -javaagent path.
export JAVA_TOOL_OPTIONS="-javaagent:\"$AGENT\""
echo "Launching Lunar with JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
exec "$LUNAR" "$@"
