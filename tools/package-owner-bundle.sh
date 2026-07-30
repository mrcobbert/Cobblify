#!/usr/bin/env bash
#
# Builds and packages the owner-baked distribution bundles (plan D8).
#
# Owner-run, local, Mac. Produces dist-owner/ (gitignored) containing:
#   * the baked Forge jar, copied as-is
#   * Cobblify-Lunar-<version>.zip: Weave agent + Lunar jar + both installers
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
# OWNER: fill in the real hash ONCE by running
#   shasum -a 256 ~/.weave/Weave-Loader-Agent-1.3.3.jar
# and pasting the 64-hex-char digest below. The script refuses to run while
# the placeholder is in place.
WEAVE_AGENT_PATH="${WEAVE_AGENT_PATH:-$HOME/.weave/Weave-Loader-Agent-1.3.3.jar}"
WEAVE_AGENT_SHA256="REPLACE_WITH_REAL_SHA256_FROM_shasum_-a_256"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

die() { echo "error: $*" >&2; exit 1; }

# One temp directory for grep patterns and zip staging, trapped immediately so
# no secret-bearing pattern file can outlive the run.
tmpd=$(mktemp -d) || die "mktemp failed"
chmod 700 "$tmpd"
trap 'rm -rf "$tmpd"' EXIT

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

# --- assemble dist-owner/ ----------------------------------------------------

dist="$repo_root/dist-owner"
rm -rf "$dist"
mkdir -p "$dist"

cp "$forge_jar" "$dist/"

bundle="$dist/Cobblify-Lunar-$version.zip"
stage="$tmpd/stage"
mkdir "$stage"
cp "$WEAVE_AGENT_PATH" "$lunar_jar" "$installer_cmd" "$installer_bat" "$stage/"
( cd "$stage" && zip -q -X "$bundle" \
    "$(basename "$WEAVE_AGENT_PATH")" \
    "$(basename "$lunar_jar")" \
    "Install BedwarsQOL (Lunar).command" \
    "Install BedwarsQOL (Lunar).bat" ) \
  || die "zip failed"

# --- assertion: bundle lists exactly the 4 expected entries ------------------

zipinfo -1 "$bundle" | LC_ALL=C sort > "$tmpd/got_entries" \
  || die "could not list the bundle contents"
LC_ALL=C sort > "$tmpd/want_entries" <<EOF
$(basename "$WEAVE_AGENT_PATH")
$(basename "$lunar_jar")
Install BedwarsQOL (Lunar).command
Install BedwarsQOL (Lunar).bat
EOF
if ! cmp -s "$tmpd/got_entries" "$tmpd/want_entries"; then
  echo "error: bundle entry list is not the expected 4 entries:" >&2
  diff "$tmpd/want_entries" "$tmpd/got_entries" >&2 || true
  exit 1
fi
echo "bundle entries verified (4/4)"

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
echo "hygiene check passed (no tracked file, no staged diff)"

# --- scrub every plaintext intermediate from both builds ---------------------
#
# Known copies: build/generated/backendProps/ (both builds), the Forge
# processed-resource copy under build/classes/java/main, and Lunar's
# build/resources/{main,test}. The find below deletes ALL on-disk copies under
# both build dirs (jar-internal copies are the product and are untouched),
# then re-runs to assert none remain.

forge_build="$repo_root/versions/1.8.9-forge/build"
lunar_build="$repo_root/lunar/build"
find "$forge_build" "$lunar_build" -type f -name cobblify-backend.properties -exec rm -f {} +
rm -rf "$forge_build/generated/backendProps" "$lunar_build/generated/backendProps"
leftover=$(find "$forge_build" "$lunar_build" -type f -name cobblify-backend.properties | wc -l | tr -d '[:space:]')
[ "$leftover" = "0" ] || die "plaintext cobblify-backend.properties still present under a build dir after scrub"
echo "plaintext intermediates scrubbed"

# --- done --------------------------------------------------------------------

echo
echo "owner bundle ready:"
echo "  $dist/$(basename "$forge_jar")"
echo "  $bundle"
echo
echo "Distribute these PRIVATELY (DM). Never attach them to a public GitHub"
echo "Release - public releases carry only the blank CI jars."
