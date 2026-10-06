#!/usr/bin/env bash
#
# Builds "Cobblify Launcher (Next)": a local test build of the launcher that can
# sit beside the stable one, and hands it over in /Users/Shared.
#
# What makes it safe to open (see .ai research BUILD-PLAN "Release safety"):
#   - Its own identity (club.cobblify.launcher.next), so its single-instance lock
#     and ~/Library webview folders are its own. Everything else the launcher
#     touches is in $HOME and shared with the stable launcher, so only open it
#     with your real setup set aside (tools/fresh-mac.sh aside).
#   - No updater: built with the three compile-time updater values unset, then
#     checked with `strings` for the update host.
#   - Only the launcher carries the prerelease version (0.18.0-next.N). The mod
#     jars keep their release name and version: the launcher rejects a
#     prerelease jar name (resources.rs is_release_jar_name).
#
# The jars come from a vendor dir holding a released launcher's
# Contents/Resources/resources: three jars plus its manifest.json. Every jar is
# checked against that manifest, and the Weave agent also against the hash
# pinned in .github/workflows/launcher-update.yml.
#
# Usage: launcher/tools/next-build.sh <N> --vendor <dir> [--quarantine] [--replace]
#   <N>           the build number: version 0.18.0-next.<N>
#   --vendor      the vendor dir (e.g. .ai/vendor, gitignored)
#   --quarantine  mark the delivered app as downloaded, so Gatekeeper asks the
#                 way it would for a real download
#   --replace     overwrite an earlier delivery of the same version
#
# Output: /Users/Shared/CobblifyNext/0.18.0-next.<N>/Cobblify Launcher (Next).app
# (NEXT_DELIVER_ROOT overrides the root). /Users/Shared is sticky: copy the app
# out (Finder copy or `cp -R`), don't move it.

set -euo pipefail

die() { echo "next-build: $*" >&2; exit 1; }
step() { echo "==> $*"; }
check() { echo "  ok  $*"; }

NEXT_BASE_VERSION="0.18.0"
NEXT_NAME="Cobblify Launcher (Next)"
NEXT_IDENTIFIER="club.cobblify.launcher.next"
# Hosts an updater-enabled build embeds (updater.rs: the URL is compiled in
# verbatim). The path /launcher/update is in every build, so grep the host.
UPDATE_HOST_PATTERN='workers\.dev|bedwarsqol-stats'
# Always in the binary (updater.rs). Proves `strings` actually read it.
ALWAYS_PRESENT='/launcher/update-event'

n="" vendor="" quarantine=0 replace=0
while [ $# -gt 0 ]; do
  case "$1" in
    --vendor) [ $# -ge 2 ] || die "--vendor needs a directory"; vendor=$2; shift 2 ;;
    --quarantine) quarantine=1; shift ;;
    --replace) replace=1; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) [ -z "$n" ] || die "one build number only"; n=$1; shift ;;
  esac
done
printf '%s' "$n" | grep -Eq '^[1-9][0-9]*$' || die "usage: next-build.sh <N> --vendor <dir> (N a positive integer)"
[ -n "$vendor" ] || die "--vendor <dir> is required"
[ "$(uname -s)" = "Darwin" ] || die "macOS only"

version="$NEXT_BASE_VERSION-next.$n"
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)
launcher="$repo_root/launcher"
vendor=$(cd "$vendor" 2>/dev/null && pwd -P) || die "vendor dir not found"
deliver_root=${NEXT_DELIVER_ROOT:-/Users/Shared/CobblifyNext}

COBBLIFY_APP_NAME="$NEXT_NAME.app"
COBBLIFY_APP_BUILD_DIR="launcher/src-tauri/target/release/bundle/macos"
. "$launcher/tools/app-inject-lib.sh"

json_field() { plutil -extract "$2" raw -o - "$1" 2>/dev/null || die "$1 has no $2"; }
sha() { shasum -a 256 "$1" | awk '{print $1}'; }

# 1. The vendor jars are the released ones.
step "checking vendor jars in $vendor"
vm="$vendor/manifest.json"
[ -f "$vm" ] || die "$vm not found"
mod_jar=$(json_field "$vm" mod_jar)
agent_jar=$(json_field "$vm" agent_jar)
forge_jar=$(json_field "$vm" forge_jar)
mod_version=$(json_field "$vm" mod_version)
printf '%s' "$mod_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || die "vendor mod_version '$mod_version' is not X.Y.Z; the launcher only accepts release-named jars"
for pair in "mod_jar:mod_sha256" "agent_jar:agent_sha256" "forge_jar:forge_sha256"; do
  name=$(json_field "$vm" "${pair%%:*}")
  want=$(json_field "$vm" "${pair##*:}")
  [ -f "$vendor/$name" ] || die "vendor jar $name missing"
  got=$(sha "$vendor/$name")
  [ "$got" = "$want" ] || die "$name sha256 $got does not match the vendor manifest ($want)"
  check "$name matches the vendor manifest"
done
pinned=$(grep -Eo 'WEAVE_AGENT_SHA256: *[0-9a-f]{64}' "$repo_root/.github/workflows/launcher-update.yml" \
  | awk '{print $2}' | sort -u)
[ -n "$pinned" ] || die "no WEAVE_AGENT_SHA256 pinned in launcher-update.yml"
[ "$(printf '%s\n' "$pinned" | wc -l | tr -d ' ')" = 1 ] || die "launcher-update.yml pins more than one agent hash"
[ "$(sha "$vendor/$agent_jar")" = "$pinned" ] || die "$agent_jar does not match the hash pinned in launcher-update.yml"
check "$agent_jar matches launcher-update.yml"

# 2. Build, blank, with no updater.
step "building $NEXT_NAME $version (updater values unset)"
if [ -n "$(find "$launcher/src-tauri/resources" \( -name '*.jar' -o -name manifest.json \) -print -quit)" ]; then
  die "launcher/src-tauri/resources holds staged jars or a manifest; the build must be blank"
fi
config=$(printf '{"productName":"%s","identifier":"%s","version":"%s"}' "$NEXT_NAME" "$NEXT_IDENTIFIER" "$version")
(cd "$launcher" && env -u COBBLIFY_UPDATE_URL -u COBBLIFY_UPDATE_TOKEN -u COBBLIFY_UPDATER_PUBKEY \
  npx --no-install tauri build --bundles app --config "$config") || die "tauri build failed"
built="$repo_root/$COBBLIFY_APP_BUILD_DIR/$COBBLIFY_APP_NAME"
cobblify_assert_blank_app "$built"
check "build is blank"

# 3. Stage a copy, inject, sign. Never under /tmp: tauri refuses a symlinked
# ancestor (README "never run from /tmp").
step "injecting and signing"
stage_dir="$launcher/src-tauri/target/next/$version"
rm -rf "$stage_dir"
mkdir -p "$stage_dir"
ditto "$built" "$stage_dir/$COBBLIFY_APP_NAME"
app="$stage_dir/$COBBLIFY_APP_NAME"
cobblify_inject_app "$app" "$vendor/$mod_jar" "$vendor/$agent_jar" "$mod_version" "$vendor/$forge_jar"
cobblify_sign_app "$app"
check "injected and ad-hoc signed"

# 4. Verify what will be handed over.
verify_app() {
  local a=$1 plist="$1/Contents/Info.plist" bin="$1/Contents/MacOS/cobblify-launcher" res="$1/$COBBLIFY_APP_RESOURCES"
  [ "$(plutil -extract CFBundleIdentifier raw -o - "$plist")" = "$NEXT_IDENTIFIER" ] || die "$a: wrong bundle identifier"
  [ "$(plutil -extract CFBundleName raw -o - "$plist")" = "$NEXT_NAME" ] || die "$a: wrong bundle name"
  [ "$(plutil -extract CFBundleShortVersionString raw -o - "$plist")" = "$version" ] || die "$a: wrong version"
  check "identity $NEXT_IDENTIFIER, \"$NEXT_NAME\", $version"
  codesign --verify --deep --strict "$a" || die "$a: codesign --verify failed"
  check "codesign --verify --deep --strict"
  local s; s=$(strings -a "$bin")
  # Here-strings, not pipes: under pipefail, `grep -q` closing the pipe early
  # makes the writer die of SIGPIPE and the whole test read as failed.
  grep -qF "$ALWAYS_PRESENT" <<<"$s" || die "$a: strings did not find $ALWAYS_PRESENT; the update-host check would prove nothing"
  if grep -Eqi "$UPDATE_HOST_PATTERN" <<<"$s"; then die "$a: binary contains an update host"; fi
  check "no update host in the binary"
  local f want got
  for f in mod_jar:mod_sha256 agent_jar:agent_sha256 forge_jar:forge_sha256; do
    want=$(json_field "$vm" "${f##*:}")
    [ "$(json_field "$res/manifest.json" "${f##*:}")" = "$want" ] || die "$a: manifest ${f##*:} differs from the vendor"
    got=$(sha "$res/$(json_field "$res/manifest.json" "${f%%:*}")")
    [ "$got" = "$want" ] || die "$a: bundled $(json_field "$res/manifest.json" "${f%%:*}") differs from the vendor"
  done
  check "bundled jars match the vendor manifest"
}
step "verifying the staged app"
verify_app "$app"

# 5. Deliver.
step "delivering to $deliver_root/$version"
dest_dir="$deliver_root/$version"
if [ -e "$dest_dir" ]; then
  [ "$replace" = 1 ] || die "$dest_dir exists; pass --replace to overwrite it"
  rm -rf "$dest_dir"
fi
mkdir -p "$dest_dir"
ditto "$app" "$dest_dir/$COBBLIFY_APP_NAME"
chmod -R a+rX "$deliver_root"
if [ "$quarantine" = 1 ]; then
  xattr -w com.apple.quarantine "0081;$(printf '%x' "$(date +%s)");next-build;" "$dest_dir/$COBBLIFY_APP_NAME"
  check "quarantined"
fi
step "verifying the delivered copy"
verify_app "$dest_dir/$COBBLIFY_APP_NAME"

echo
echo "Delivered: $dest_dir/$COBBLIFY_APP_NAME"
echo "  binary sha256 $(sha "$dest_dir/$COBBLIFY_APP_NAME/Contents/MacOS/cobblify-launcher")"
echo "Copy it (don't move it) to ~/Applications, and only open it with your setup set aside:"
echo "  launcher/tools/fresh-mac.sh aside all"
