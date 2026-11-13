#!/bin/bash
# Cobblify — one-click installer for Lunar Client (macOS).
# Old plain releases are backed up outside Weave's mods directory before an upgrade.

DIR="$(cd "$(dirname "$0")" && pwd)" || exit 1
WEAVE="$HOME/.weave"
MODS="$WEAVE/mods"
BACKUPS="$WEAVE/cobblify-backups"
LAUNCHER="Launch Lunar (Cobblify).command"
stage=""; backup=""; committed=0
saved_from=(); saved_to=(); installed=()

fail() { echo "  ERROR: $*" >&2; exit 1; }

finish() {
  local rc=$? i
  trap - EXIT
  if [ "$committed" -eq 0 ]; then
    for ((i=${#installed[@]}-1; i>=0; i--)); do
      rm -f "${installed[i]}" || echo "  Could not remove incomplete file: ${installed[i]}" >&2
    done
    for ((i=${#saved_from[@]}-1; i>=0; i--)); do
      mv "${saved_from[i]}" "${saved_to[i]}" || echo "  Previous file remains in: ${saved_from[i]}" >&2
    done
  fi
  [ -z "$stage" ] || rm -rf "$stage"
  [ -z "$backup" ] || rmdir "$backup" 2>/dev/null || true
  echo ""
  read -r -p "  Press Return to close." || true
  exit "$rc"
}
trap finish EXIT

plain_dir() {
  [ ! -L "$1" ] || fail "Cannot install through a linked folder: $1"
  [ ! -e "$1" ] || [ -d "$1" ] || fail "Expected a folder: $1"
}
plain_file() {
  [ ! -L "$1" ] || fail "Cannot replace a linked file: $1"
  [ ! -e "$1" ] || [ -f "$1" ] || fail "Expected a regular file: $1"
}

# Compare decimal components without shell arithmetic (leading zeroes and large numbers are safe).
superseded_release() {
  local name=$1 component a b i
  local theirs=() ours=()
  [[ "$name" =~ ^Cobblify-Lunar-([0-9]+)\.([0-9]+)\.([0-9]+)\.jar$ ]] || return 1
  theirs=("${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}")
  [[ "$MOD" =~ ^Cobblify-Lunar-([0-9]+)\.([0-9]+)\.([0-9]+)\.jar$ ]] || return 1
  ours=("${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}")
  for i in 0 1 2; do
    a=${theirs[i]}; b=${ours[i]}
    while [ "${#a}" -gt 1 ] && [[ "$a" == 0* ]]; do a=${a#0}; done
    while [ "${#b}" -gt 1 ] && [[ "$b" == 0* ]]; do b=${b#0}; done
    [ "${#a}" -lt "${#b}" ] && return 0
    [ "${#a}" -gt "${#b}" ] && return 1
    [[ "$a" < "$b" ]] && return 0
    [[ "$a" > "$b" ]] && return 1
  done
  return 0
}

save_file() {
  local path=$1 name=$2
  plain_file "$path"
  [ -e "$path" ] || return 0
  mv "$path" "$backup/$name" || fail "Could not back up $path. Close Lunar and try again."
  saved_from+=("$backup/$name"); saved_to+=("$path")
}
install_file() {
  mv "$1" "$2" || fail "Could not install $2."
  installed+=("$2")
}

echo ""
echo "  Installing Cobblify for Lunar Client..."
shopt -s nullglob nocaseglob nocasematch
agents=("$DIR"/Weave-Loader-Agent-*.jar)
mods=("$DIR"/Cobblify-Lunar-*.jar)
[ "${#agents[@]}" -eq 1 ] && [ "${#mods[@]}" -eq 1 ] \
  || fail "Expected exactly one Weave-Loader-Agent jar and one Cobblify-Lunar jar beside this installer. Extract the bundle into a fresh folder."
AGENT="$(basename "${agents[0]}")"
MOD="$(basename "${mods[0]}")"
[[ "$AGENT" =~ ^Weave-Loader-Agent-[A-Za-z0-9._-]+\.jar$ ]] || fail "Unexpected agent filename."
[[ "$MOD" =~ ^Cobblify-Lunar-[0-9]+\.[0-9]+\.[0-9]+\.jar$ ]] || fail "Expected a plain Cobblify release filename."
plain_file "${agents[0]}"; plain_file "${mods[0]}"
plain_dir "$WEAVE"; plain_dir "$MODS"; plain_dir "$BACKUPS"; plain_dir "$HOME/Desktop"
[ -d "$HOME/Desktop" ] || fail "Desktop folder was not found."
separate_launchers=1
[ "$DIR" -ef "$HOME/Desktop" ] && separate_launchers=0
plain_file "$WEAVE/$AGENT"
plain_file "$DIR/$LAUNCHER"; plain_file "$HOME/Desktop/$LAUNCHER"
peers=("$MODS"/Cobblify-Lunar-*.jar)
for peer in "${peers[@]}"; do
  plain_file "$peer"
  superseded_release "$(basename "$peer")" \
    || fail "Conflicting Cobblify jar: $(basename "$peer"). Move it out of Weave's mods folder before installing."
done

mkdir -p "$MODS" "$BACKUPS" || fail "Could not create Weave's folders."
stage=$(mktemp -d "$WEAVE/.cobblify-install.XXXXXX") || fail "Could not stage the install."
cp "${agents[0]}" "$stage/agent.jar" && cmp -s "${agents[0]}" "$stage/agent.jar" \
  || fail "Could not copy and verify the Weave agent."
cp "${mods[0]}" "$stage/mod.jar" && cmp -s "${mods[0]}" "$stage/mod.jar" \
  || fail "Could not copy and verify the Cobblify mod."

cat > "$stage/launcher" <<LAUNCHEOF
#!/bin/bash
# Launch the official Lunar Client with Cobblify (Weave) injected.
# Fully quit Lunar first (Cmd+Q), then double-click this file.

AGENT="\$HOME/.weave/$AGENT"
LUNAR="/Applications/Lunar Client.app/Contents/MacOS/Lunar Client"

if [ ! -f "\$AGENT" ]; then
  echo "ERROR: Weave agent not found at: \$AGENT"
  echo "   Run the Cobblify installer again."
  read -p "Press Return to close."
  exit 1
fi
if [ ! -x "\$LUNAR" ]; then
  echo "ERROR: Lunar Client not found at: \$LUNAR"
  echo "   Install Lunar Client first, then run this again."
  read -p "Press Return to close."
  exit 1
fi

echo "If Lunar is already open, fully quit it (Cmd+Q) first, then run this again."
# Embedded quotes keep a home directory containing spaces as one JVM option.
export JAVA_TOOL_OPTIONS="-javaagent:\"\$HOME/.weave/$AGENT\""
exec "\$LUNAR" "\$@"
LAUNCHEOF
[ "$?" -eq 0 ] || fail "Could not stage the launcher."
chmod +x "$stage/launcher" || fail "Could not prepare the launcher."
cp "$stage/launcher" "$stage/desktop-launcher" \
  && cmp -s "$stage/launcher" "$stage/desktop-launcher" || fail "Could not stage the Desktop launcher."

backup=$(mktemp -d "$BACKUPS/install.XXXXXX") || fail "Could not create an install backup."
for peer in "${peers[@]}"; do save_file "$peer" "mod-$(basename "$peer")"; done
save_file "$WEAVE/$AGENT" "agent-$AGENT"
save_file "$DIR/$LAUNCHER" "bundle-launcher"
if [ "$separate_launchers" -eq 1 ]; then save_file "$HOME/Desktop/$LAUNCHER" "desktop-launcher"; fi
install_file "$stage/agent.jar" "$WEAVE/$AGENT"
install_file "$stage/mod.jar" "$MODS/$MOD"
install_file "$stage/launcher" "$DIR/$LAUNCHER"
if [ "$separate_launchers" -eq 1 ]; then install_file "$stage/desktop-launcher" "$HOME/Desktop/$LAUNCHER"; fi
committed=1

echo "  Done."
[ "${#saved_from[@]}" -eq 0 ] || echo "  Previous files saved in: $backup"
echo ""
echo "  A launcher named '$LAUNCHER' was placed next to this installer AND on your Desktop."
echo "  Use it every time you play — it starts Lunar with Cobblify loaded."
echo ""
echo "  Remaining steps (in Lunar, after launching):"
echo "   1. Log into Lunar Client."
echo "   2. Pick version 1.8.9 and click Play."
echo "   3. Turn Waypoints OFF inside your active Lunar settings profile."
echo "   4. Press Right Shift in-game to open the Cobblify settings menu."
exit 0
