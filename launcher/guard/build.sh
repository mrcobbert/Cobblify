#!/bin/sh
# Rebuilds Cobblify-Guard.jar, the javaagent the launcher registers in Lunar (see
# src/net/cobblify/guard/Guard.java). The launcher embeds the committed jar and pins its sha256
# (src-tauri/src/guard.rs), so run this only when the source changes, then update the pin.
# Needs a JDK 17+ (javac --release 8, jar --date for a reproducible archive).
set -eu
here=$(cd "$(dirname "$0")" && pwd -P)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
javac --release 8 -nowarn -d "$out/classes" "$here/src/net/cobblify/guard/Guard.java"
jar --create --file "$here/Cobblify-Guard.jar" --manifest "$here/MANIFEST.MF" \
  --date 2026-01-01T00:00:00Z -C "$out/classes" .
shasum -a 256 "$here/Cobblify-Guard.jar"
