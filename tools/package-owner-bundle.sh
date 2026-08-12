#!/usr/bin/env bash
#
# Builds and packages the owner-baked distribution bundles (plan D8).
#
# Owner-run, local, Mac. Produces dist-owner/ (gitignored) containing:
#   * the baked Forge jar, copied as-is
#   * Cobblify-Lunar-<version>.zip: the Cobblify Launcher .app with the agent
#     and Lunar jar injected into it, plus those two jars and both installers
#     loose for anyone who prefers the manual route
#
# The launcher is built BLANK - never with a jar inside it - so no build artifact
# can ever carry the token. This script injects into a COPY in $tmpd, ad-hoc
# signs that copy, and leaves the build output untouched.
#
# The backend URL/token come from ~/.gradle/gradle.properties
# (cobblifyBackendUrl / cobblifyBackendToken) and are NEVER echoed by this
# script, passed on a command line, or left behind in a plaintext build
# intermediate. Every artifact selector is fail-closed: an unexpected or
# ambiguous file aborts the run instead of packaging the wrong thing.
#
# Distribution rule: dist-owner/ artifacts are sent privately (DM). They are
# NEVER attached to public GitHub Releases - those keep receiving only the
# blank CI jars.
#
# Usage: tools/package-owner-bundle.sh
# Exit:  0 = both artifacts built, verified, intermediates scrubbed
#        1 = any preflight, build, selection, verification or hygiene failure

set -euo pipefail

# --- pinned Weave agent ------------------------------------------------------
#
# The agent is an external artifact the owner keeps locally; refuse to package
# one that does not match the recorded hash.
#
# On an agent UPGRADE: update the path, re-run
#   shasum -a 256 ~/.weave/Weave-Loader-Agent-<new version>.jar
# and paste the new 64-hex-char digest below (a public artifact hash, not a
# secret). The script refuses to run while a placeholder is in place.
WEAVE_AGENT_PATH="${WEAVE_AGENT_PATH:-$HOME/.weave/Weave-Loader-Agent-1.3.3.jar}"
WEAVE_AGENT_SHA256="e63da5ed3cc85868088527cd7d49ebd708785b6567da389fe89a89913ef4afd2"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

die() { echo "error: $*" >&2; exit 1; }

# Launcher injection lives beside the launcher and is shared with
# launcher/tools/test-injection.sh, which drives it with fake jars so the logic
# can be proven without a token and without running this script.
inject_lib="$repo_root/launcher/tools/app-inject-lib.sh"
[ -f "$inject_lib" ] || die "missing $inject_lib"
# shellcheck source=../launcher/tools/app-inject-lib.sh
. "$inject_lib"

forge_build="$repo_root/versions/1.8.9-forge/build"
lunar_build="$repo_root/lunar/build"

# Delete every on-disk plaintext copy of the baked backend properties in both
# build trees (jar-internal copies are the product and are untouched). Known
# copies: build/generated/backendProps/ (both builds), the Forge
# processed-resource copy under build/classes/java/main, and Lunar's
# build/resources/{main,test}. Called from the happy path AND from the EXIT
# trap, so a run that fails after the builds generated resources still never
# leaves the baked url/token behind. Best-effort by construction (must never
# mask the script's real exit status).
scrub_plaintext() {
  find "$forge_build" "$lunar_build" -type f -name cobblify-backend.properties \
    -exec rm -f {} + 2>/dev/null || true
  rm -rf "$forge_build/generated/backendProps" "$lunar_build/generated/backendProps" 2>/dev/null || true
}

# One temp directory for grep patterns and zip staging, trapped immediately so
# no secret-bearing pattern file can outlive the run. The trap also scrubs the
# build trees on ANY exit; on success dist-owner/ artifacts are already
# assembled and are not touched by the scrub.
tmpd=$(mktemp -d) || die "mktemp failed"
chmod 700 "$tmpd"
trap 'scrub_plaintext; rm -rf "$tmpd"' EXIT

# --- preflight: backend properties exist (values never read into output) -----

gradle_props="$HOME/.gradle/gradle.properties"
[ -f "$gradle_props" ] || die "$gradle_props not found. Add cobblifyBackendUrl and cobblifyBackendToken there first."

# prop_value <name>: value after the first '=', surrounding whitespace trimmed.
prop_value() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=//p" "$gradle_props" | head -1 \
    | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

backend_url=$(prop_value cobblifyBackendUrl)
backend_token=$(prop_value cobblifyBackendToken)
[ -n "$backend_url" ]   || die "cobblifyBackendUrl is missing or empty in $gradle_props"
[ -n "$backend_token" ] || die "cobblifyBackendToken is missing or empty in $gradle_props"

# --- preflight: JDK 17-21 (both Gradle builds require it) --------------------

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null) \
    || die "JAVA_HOME is unset and no JDK 21 was found (/usr/libexec/java_home -v 21). Install temurin-21."
  export JAVA_HOME
fi
[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME=$JAVA_HOME has no bin/java"
java_major=$("$JAVA_HOME/bin/java" -version 2>&1 \
  | sed -n 's/^.* version "\([0-9][0-9]*\).*/\1/p' | head -1)
case "$java_major" in
  17|18|19|20|21) ;;
  *) die "JAVA_HOME points at Java '$java_major'; the builds need JDK 17-21 (use temurin-21)" ;;
esac
echo "using JDK $java_major at $JAVA_HOME"

# --- preflight: pinned Weave agent -------------------------------------------

case "$WEAVE_AGENT_SHA256" in
  REPLACE_WITH_REAL_SHA256*)
    die "WEAVE_AGENT_SHA256 is still the placeholder. Run: shasum -a 256 \"$WEAVE_AGENT_PATH\" and record the digest in this script." ;;
esac
printf '%s' "$WEAVE_AGENT_SHA256" | grep -Eq '^[0-9a-f]{64}$' \
  || die "WEAVE_AGENT_SHA256 is not a 64-char lowercase hex digest"
[ -f "$WEAVE_AGENT_PATH" ] || die "Weave agent not found at $WEAVE_AGENT_PATH"
agent_sha=$(shasum -a 256 "$WEAVE_AGENT_PATH" | awk '{print $1}')
[ "$agent_sha" = "$WEAVE_AGENT_SHA256" ] \
  || die "Weave agent SHA-256 mismatch at $WEAVE_AGENT_PATH - refusing to package an unverified agent"
echo "weave agent verified: $WEAVE_AGENT_PATH"

# --- preflight: a blank launcher .app has already been built -----------------
#
# Deliberately a CHECK, not a build. `npm run tauri build` is a multi-minute
# release compile with its own toolchain requirements (node, the Tauri CLI, a
# vite build), and burying it here would make every packaging run pay for it and
# would report its failures as packaging failures. The owner builds the launcher
# when the launcher changes; this script refuses to run against a missing one.

app_src="$repo_root/$COBBLIFY_APP_BUILD_DIR/$COBBLIFY_APP_NAME"
if [ ! -d "$app_src" ]; then
  die "no launcher build at $app_src
Build it first:
  cd \"$repo_root/launcher\" && npm run tauri build -- --bundles app
(--bundles app skips the .dmg step, which needs Finder automation and is not
part of this bundle.)"
fi
# The build must contain no jar and no manifest: those are injected into a copy
# below, and a jar inside the checked-out resources dir would be a token-baked
# artifact sitting in the working tree.
cobblify_assert_blank_app "$app_src"
launcher_resources="$repo_root/launcher/src-tauri/resources"
[ -d "$launcher_resources" ] || die "$launcher_resources is missing - the launcher tree is incomplete"
checked_out_jars=$(find "$launcher_resources" -type f -name '*.jar' -print)
[ -z "$checked_out_jars" ] || die "launcher/src-tauri/resources/ contains jar(s):
$checked_out_jars
Those are injected at package time and must never sit in the working tree."
echo "launcher build verified blank: $app_src"

# --- version (read from the Gradle projects, so filenames cannot drift) ------

forge_version=$(sed -n 's/^mod_version=//p' "$repo_root/gradle.properties" | head -1)
[ -n "$forge_version" ] || die "could not read mod_version from gradle.properties"
lunar_version=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$repo_root/lunar/build.gradle.kts" | head -1)
[ -n "$lunar_version" ] || die "could not read version from lunar/build.gradle.kts"
[ "$forge_version" = "$lunar_version" ] \
  || die "version mismatch: Forge $forge_version vs Lunar $lunar_version - align them before packaging"
version=$forge_version
echo "packaging version $version"

# --- build -------------------------------------------------------------------

echo "building Forge (:1.8.9-forge:assemble)..."
"$repo_root/gradlew" -p "$repo_root" :1.8.9-forge:assemble \
  || die "Forge assemble failed"

echo "building Lunar (assemble)..."
( cd "$repo_root/lunar" && ./gradlew assemble ) \
  || die "Lunar assemble failed"

# --- fail-closed artifact selection ------------------------------------------
#
# Exact expected names only. Any classifier (-dev, -sources, or anything else)
# produces a different filename and can never match; a missing jar aborts.

forge_libs="$repo_root/versions/1.8.9-forge/build/libs"
lunar_libs="$repo_root/lunar/build/libs"
forge_jar="$forge_libs/Cobblify-1.8.9-forge-$version.jar"
lunar_jar="$lunar_libs/Cobblify-Lunar-$version.jar"

select_one() { # <exact path> <label>
  local matches
  matches=$(find "$(dirname "$1")" -maxdepth 1 -name "$(basename "$1")" -type f | wc -l | tr -d '[:space:]')
  [ "$matches" = "1" ] || die "$2 selector matched $matches file(s) for $(basename "$1") in $(dirname "$1") - expected exactly 1"
}
select_one "$forge_jar" "Forge jar"
select_one "$lunar_jar" "Lunar jar"

installer_cmd="$repo_root/lunar/dist/Install BedwarsQOL (Lunar).command"
installer_bat="$repo_root/lunar/dist/Install BedwarsQOL (Lunar).bat"
[ -f "$installer_cmd" ] || die "missing installer: $installer_cmd"
[ -f "$installer_bat" ] || die "missing installer: $installer_bat"

# The friend-facing instructions ride INSIDE the bundle. A README that only
# exists in the repo is useless to someone who was DM'd a zip - and the first
# thing they hit is Gatekeeper blocking the app, which needs the System Settings
# steps this file spells out.
# Lives OUTSIDE launcher/dist: Vite's emptyOutDir wipes that dir on every build.
readme_txt="$repo_root/launcher/friend/READ ME FIRST.txt"
[ -f "$readme_txt" ] || die "missing friend instructions: $readme_txt"

# --- assemble dist-owner/ ----------------------------------------------------

dist="$repo_root/dist-owner"
rm -rf "$dist"
mkdir -p "$dist"

cp "$forge_jar" "$dist/"

bundle="$dist/Cobblify-Lunar-$version.zip"
stage="$tmpd/stage"
mkdir "$stage"
cp "$WEAVE_AGENT_PATH" "$lunar_jar" "$installer_cmd" "$installer_bat" "$readme_txt" "$stage/"

# --- launcher: stage, inject, sign - all inside $tmpd ------------------------
#
# The build output is READ ONLY here. Everything below happens to the copy, so a
# failed run can never leave a token-baked jar inside the tracked-adjacent build
# tree, and the next `npm run tauri build` is not required to clean up after us.

staged_app="$stage/$COBBLIFY_APP_NAME"
cp -R "$app_src" "$stage/" || die "could not stage $COBBLIFY_APP_NAME"
cobblify_assert_blank_app "$staged_app"
cobblify_inject_app "$staged_app" "$lunar_jar" "$WEAVE_AGENT_PATH" "$version"
# After injection, never before: adding files invalidates whatever signature the
# build carried. cobblify_sign_app validates with --verify --deep --strict.
cobblify_sign_app "$staged_app"
echo "launcher bundle injected and ad-hoc signed (signature validated)"

# -y stores symlinks instead of following them, -D omits directory entries so
# the archive listing is exactly the file list compared below.
( cd "$stage" && zip -q -r -X -y -D "$bundle" \
    "$COBBLIFY_APP_NAME" \
    "$(basename "$WEAVE_AGENT_PATH")" \
    "$(basename "$lunar_jar")" \
    "Install BedwarsQOL (Lunar).command" \
    "Install BedwarsQOL (Lunar).bat" \
    "READ ME FIRST.txt" ) \
  || die "zip failed"

# --- assertion: the bundle holds exactly the expected paths, no more ---------
#
# The expected side is built from the known artifact list (the two installers,
# the two jars, and the literal blank-bundle layout in app-inject-lib.sh) - NOT
# by walking $stage. Generating both sides from the staging tree would compare
# it against itself and accept any unexpected file that got in there.

zipinfo -1 "$bundle" | LC_ALL=C sort > "$tmpd/got_entries" \
  || die "could not list the bundle contents"
{
  printf '%s\n' \
    "$(basename "$WEAVE_AGENT_PATH")" \
    "$(basename "$lunar_jar")" \
    "Install BedwarsQOL (Lunar).command" \
    "Install BedwarsQOL (Lunar).bat" \
    "READ ME FIRST.txt"
  cobblify_app_zip_entries "$COBBLIFY_APP_NAME" \
    "$(basename "$lunar_jar")" "$(basename "$WEAVE_AGENT_PATH")"
} | LC_ALL=C sort > "$tmpd/want_entries"
if ! cmp -s "$tmpd/got_entries" "$tmpd/want_entries"; then
  echo "error: the bundle does not hold exactly the expected paths" >&2
  echo "  (< expected, > actually in the zip)" >&2
  diff "$tmpd/want_entries" "$tmpd/got_entries" >&2 || true
  exit 1
fi
echo "bundle entries verified ($(wc -l < "$tmpd/want_entries" | tr -d '[:space:]') paths, exact match)"

# --- assertion: both jars carry the same non-empty baked url + token ---------
#
# Parsed comparison only; the values themselves are never printed.

jar_prop() { # <jar> <key> -> value on stdout
  unzip -p "$1" cobblify-backend.properties 2>/dev/null \
    | sed -n "s/^$2=//p" | head -1
}
f_url=$(jar_prop "$forge_jar" url);     f_tok=$(jar_prop "$forge_jar" token)
l_url=$(jar_prop "$lunar_jar" url);     l_tok=$(jar_prop "$lunar_jar" token)
[ -n "$f_url" ] || die "Forge jar: baked url is empty or cobblify-backend.properties missing"
[ -n "$f_tok" ] || die "Forge jar: baked token is empty"
[ -n "$l_url" ] || die "Lunar jar: baked url is empty or cobblify-backend.properties missing"
[ -n "$l_tok" ] || die "Lunar jar: baked token is empty"
[ "$f_url" = "$l_url" ] || die "baked url differs between the Forge and Lunar jars"
[ "$f_tok" = "$l_tok" ] || die "baked token differs between the Forge and Lunar jars"
echo "baked backend properties verified (non-empty, identical across jars)"

# --- hygiene (D9): real values in no tracked file, no staged diff ------------
#
# Patterns go through a temp file, never argv (no shell history, no ps
# exposure). Matches are reported as file names only - never the values.

pat="$tmpd/patterns"
printf '%s\n%s\n' "$backend_url" "$backend_token" > "$pat"
chmod 600 "$pat"

set +e
git grep -I -l -F -f "$pat" -- . > "$tmpd/hits"
grep_status=$?
set -e
case "$grep_status" in
  0) echo "error: the real backend url/token appear in tracked file(s):" >&2
     sed 's/^/  /' "$tmpd/hits" >&2
     exit 1 ;;
  1) ;;  # no hits - the only acceptable outcome
  *) die "git grep failed (status $grep_status); cannot establish hygiene - refusing to package" ;;
esac

git diff --cached > "$tmpd/staged" || die "git diff --cached failed; cannot establish hygiene"
set +e
grep -q -F -f "$pat" "$tmpd/staged"
grep_status=$?
set -e
case "$grep_status" in
  0) die "the real backend url/token appear in the staged diff - unstage before packaging" ;;
  1) ;;
  *) die "grep over the staged diff failed (status $grep_status); refusing to package" ;;
esac

# --- hygiene: no jar under launcher/ is tracked or staged --------------------
#
# The two content greps above CANNOT see this. `git grep -I` skips binary files
# entirely, and `git diff --cached` renders a jar as "Binary files ... differ"
# with no content to match. A force-added (`git add -f`) token-baked jar would
# therefore pass both and end up in a public push. Only a PATH check closes it,
# so this asks the index and the staged name list directly.

git ls-files -- launcher > "$tmpd/tracked_launcher" \
  || die "git ls-files failed; cannot establish hygiene"
if grep -Ei '\.jar$' "$tmpd/tracked_launcher" > "$tmpd/tracked_jars"; then
  echo "error: jar file(s) under launcher/ are TRACKED by git:" >&2
  sed 's/^/  /' "$tmpd/tracked_jars" >&2
  echo "  These are token-baked. Run: git rm --cached <path>" >&2
  exit 1
fi

git diff --cached --name-only -- launcher > "$tmpd/staged_launcher" \
  || die "git diff --cached --name-only failed; cannot establish hygiene"
if grep -Ei '\.jar$' "$tmpd/staged_launcher" > "$tmpd/staged_jars"; then
  echo "error: jar file(s) under launcher/ are STAGED for commit:" >&2
  sed 's/^/  /' "$tmpd/staged_jars" >&2
  exit 1
fi

echo "hygiene check passed (no tracked file, no staged diff, no jar under launcher/)"

# --- scrub every plaintext intermediate from both builds ---------------------
#
# scrub_plaintext (also wired into the EXIT trap for failed runs) deletes ALL
# on-disk copies under both build dirs; the find below re-runs to assert none
# remain - the assertion belongs to the happy path only, the trap stays
# best-effort.

scrub_plaintext
leftover=$(find "$forge_build" "$lunar_build" -type f -name cobblify-backend.properties | wc -l | tr -d '[:space:]')
[ "$leftover" = "0" ] || die "plaintext cobblify-backend.properties still present under a build dir after scrub"
echo "plaintext intermediates scrubbed"

# --- done --------------------------------------------------------------------

echo
echo "owner bundle ready:"
echo "  $dist/$(basename "$forge_jar")"
echo "  $bundle  (contains $COBBLIFY_APP_NAME, both jars, both installers)"
echo
echo "Distribute these PRIVATELY (DM). Never attach them to a public GitHub"
echo "Release - public releases carry only the blank CI jars."
