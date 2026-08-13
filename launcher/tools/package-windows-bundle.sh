#!/usr/bin/env bash
#
# Assembles the Windows friend bundle (Windows-port PLAN Phase 6).
#
# Owner-run, local, Mac. This machine cannot build Windows binaries, so the exe
# comes from a TRUSTED, verified run of the launcher-windows CI job; the mod jar
# is built locally with the baked backend properties, like
# tools/package-owner-bundle.sh. Output:
#   dist-owner/Cobblify-Windows-<version>.zip
#     Cobblify (Windows)/
#       Cobblify Launcher.exe
#       resources/            (mod jar, Weave agent jar, manifest.json)
#       READ ME FIRST.txt
#
# Provenance is fail-closed (PLAN round-1 finding I4): the CI run ID and the
# trusted commit are BOTH explicit arguments; the run must be completed and
# successful, its headSha must equal the trusted commit, and this checkout must
# be sitting on that same commit. Any mismatch aborts before a jar is touched,
# so a stale run or wrong branch can never be zipped next to token-baked jars.
#
# The backend url/token are never echoed, never passed on a command line, and
# never left in a plaintext intermediate (same rules as the Mac owner script).
# Distribution rule: dist-owner/ artifacts are sent privately (DM), never
# attached to public GitHub Releases.
#
# Usage: launcher/tools/package-windows-bundle.sh <ci run id> <trusted commit>
# Exit:  0 = bundle built and verified; the exe SHA-256 was printed - record it
#            in .ai/HANDOFF.md's packaging record
#        1 = any preflight, provenance, build, selection or verification failure

set -euo pipefail

# --- pinned Weave agent ------------------------------------------------------
#
# Same pin as tools/package-owner-bundle.sh - on an agent UPGRADE update BOTH
# scripts (the hash is a public artifact digest, not a secret).
WEAVE_AGENT_PATH="${WEAVE_AGENT_PATH:-$HOME/.weave/Weave-Loader-Agent-1.3.3.jar}"
WEAVE_AGENT_SHA256="e63da5ed3cc85868088527cd7d49ebd708785b6567da389fe89a89913ef4afd2"

CI_ARTIFACT_NAME="Cobblify-Launcher-Windows"
BUNDLE_DIR_NAME="Cobblify (Windows)"
EXE_SHIP_NAME="Cobblify Launcher.exe"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$repo_root"

die() { echo "error: $*" >&2; exit 1; }

[ $# -eq 2 ] || die "usage: launcher/tools/package-windows-bundle.sh <ci run id> <trusted commit>"
run_id=$1
trusted_ref=$2

command -v gh >/dev/null 2>&1 || die "the gh CLI is required (brew install gh)"

lunar_build="$repo_root/lunar/build"

# Delete every on-disk plaintext copy of the baked backend properties from the
# Lunar build tree (the only tree this script builds). Wired into the EXIT trap
# so failed runs never leave the baked url/token behind.
scrub_plaintext() {
  find "$lunar_build" -type f -name cobblify-backend.properties \
    -exec rm -f {} + 2>/dev/null || true
  rm -rf "$lunar_build/generated/backendProps" 2>/dev/null || true
}

tmpd=$(mktemp -d) || die "mktemp failed"
chmod 700 "$tmpd"
trap 'scrub_plaintext; rm -rf "$tmpd"' EXIT

# --- provenance: the CI run is exactly the trusted commit, and so are we -----

trusted_sha=$(git rev-parse --verify --quiet "${trusted_ref}^{commit}") \
  || die "'$trusted_ref' is not a commit this checkout knows"

# Gate on the job that PRODUCES the exe, not the whole run: the mod-build
# jobs in the same workflow take 20+ minutes (LaneContrastTest) and are
# irrelevant to this artifact's provenance.
job_state=$(gh run view "$run_id" --json jobs \
    --jq '.jobs[] | select(.name == "Launcher (Windows)") | "\(.status) \(.conclusion)"') \
  || die "could not read CI run $run_id (is the run id right, and gh logged in?)"
[ -n "$job_state" ] || die "CI run $run_id has no 'Launcher (Windows)' job"
[ "$job_state" = "completed success" ] \
  || die "CI run $run_id: Launcher (Windows) job is '$job_state', not 'completed success'"
run_sha=$(gh run view "$run_id" --json headSha --jq .headSha)
[ "$run_sha" = "$trusted_sha" ] \
  || die "CI run $run_id built $run_sha but the trusted commit is $trusted_sha - refusing"

local_sha=$(git rev-parse HEAD)
[ "$local_sha" = "$trusted_sha" ] \
  || die "this checkout is at $local_sha, not the trusted commit $trusted_sha - check it out first so the jars match the exe"

# HEAD equality alone is not enough: the jar is built from the WORKING TREE, so
# any dirty jar-input file would ship bytes the trusted commit does not
# identify. Scoped to the inputs of the Lunar build (build outputs and the
# local secret-properties generation live under gitignored build/ and cannot
# appear here); both tracked modifications and untracked files fail.
# lunar/gradlew is in the set because this script EXECUTES it - a dirty
# wrapper could replace the whole build while HEAD still matches.
jar_input_paths=(lunar/src lunar/build.gradle.kts lunar/settings.gradle.kts \
  lunar/gradle.properties lunar/gradle lunar/gradlew common/src)
dirty=$(git status --porcelain -- "${jar_input_paths[@]}")
[ -z "$dirty" ] || die "jar source inputs differ from the trusted commit:
$dirty
Commit or stash these first - the jars must be built from exactly $trusted_sha."
echo "provenance verified: run $run_id = commit $trusted_sha = local HEAD, jar inputs clean"

# --- preflight: backend properties exist (values never read into output) -----

gradle_props="$HOME/.gradle/gradle.properties"
[ -f "$gradle_props" ] || die "$gradle_props not found. Add cobblifyBackendUrl and cobblifyBackendToken there first."
grep -q '^[[:space:]]*cobblifyBackendUrl[[:space:]]*=..*' "$gradle_props" \
  || die "cobblifyBackendUrl is missing or empty in $gradle_props"
grep -q '^[[:space:]]*cobblifyBackendToken[[:space:]]*=..*' "$gradle_props" \
  || die "cobblifyBackendToken is missing or empty in $gradle_props"

# --- preflight: JDK 17-21 ----------------------------------------------------

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21 2>/dev/null) \
    || die "JAVA_HOME is unset and no JDK 21 was found. Install temurin-21."
  export JAVA_HOME
fi
[ -x "$JAVA_HOME/bin/java" ] || die "JAVA_HOME=$JAVA_HOME has no bin/java"

# --- preflight: pinned Weave agent -------------------------------------------

[ -f "$WEAVE_AGENT_PATH" ] || die "Weave agent not found at $WEAVE_AGENT_PATH"
agent_sha=$(shasum -a 256 "$WEAVE_AGENT_PATH" | awk '{print $1}')
[ "$agent_sha" = "$WEAVE_AGENT_SHA256" ] \
  || die "Weave agent SHA-256 mismatch at $WEAVE_AGENT_PATH - refusing to package an unverified agent"
echo "weave agent verified: $WEAVE_AGENT_PATH"

# --- version -----------------------------------------------------------------

version=$(sed -n 's/^version = "\(.*\)"$/\1/p' "$repo_root/lunar/build.gradle.kts" | head -1)
[ -n "$version" ] || die "could not read version from lunar/build.gradle.kts"
echo "packaging version $version"

# --- build the baked Lunar jar -----------------------------------------------

echo "building Lunar (assemble)..."
( cd "$repo_root/lunar" && ./gradlew assemble ) || die "Lunar assemble failed"

lunar_jar="$repo_root/lunar/build/libs/Cobblify-Lunar-$version.jar"
[ -f "$lunar_jar" ] || die "expected exactly $lunar_jar after the build"

# Parsed check only; the values are never printed.
jar_prop() { unzip -p "$1" cobblify-backend.properties 2>/dev/null | sed -n "s/^$2=//p" | head -1; }
[ -n "$(jar_prop "$lunar_jar" url)" ] || die "Lunar jar: baked url is empty or cobblify-backend.properties missing"
[ -n "$(jar_prop "$lunar_jar" token)" ] || die "Lunar jar: baked token is empty"
echo "baked backend properties verified (non-empty)"

# --- fetch and verify the CI exe ---------------------------------------------

gh run download "$run_id" -n "$CI_ARTIFACT_NAME" -D "$tmpd/exe" \
  || die "could not download artifact $CI_ARTIFACT_NAME from run $run_id (expired after 7 days?)"
exe_count=$(find "$tmpd/exe" -type f -name '*.exe' | wc -l | tr -d '[:space:]')
[ "$exe_count" = "1" ] || die "artifact $CI_ARTIFACT_NAME held $exe_count exe file(s), expected exactly 1"
exe_src=$(find "$tmpd/exe" -type f -name '*.exe')
exe_sha=$(shasum -a 256 "$exe_src" | awk '{print $1}')
echo "exe verified: $(basename "$exe_src") sha256=$exe_sha"

# --- stage the bundle --------------------------------------------------------

stage="$tmpd/stage/$BUNDLE_DIR_NAME"
res="$stage/resources"
mkdir -p "$res"

cp "$exe_src" "$stage/$EXE_SHIP_NAME"
readme="$repo_root/launcher/friend/READ ME FIRST (Windows).txt"
[ -f "$readme" ] || die "missing friend instructions: $readme"
cp "$readme" "$stage/READ ME FIRST.txt"

mod_name=$(basename "$lunar_jar")
agent_name=$(basename "$WEAVE_AGENT_PATH")
cp "$lunar_jar" "$res/$mod_name"
cp "$WEAVE_AGENT_PATH" "$res/$agent_name"

# Same manifest shape and same hash-the-staged-copy rule as
# launcher/tools/app-inject-lib.sh (resources.rs, deny_unknown_fields).
printf '%s' "$version" | grep -Eq '^[0-9A-Za-z._+-]+$' \
  || die "version '$version' is not safe to write into manifest.json"
mod_sha=$(shasum -a 256 "$res/$mod_name" | awk '{print $1}')
staged_agent_sha=$(shasum -a 256 "$res/$agent_name" | awk '{print $1}')
[ "$staged_agent_sha" = "$WEAVE_AGENT_SHA256" ] || die "staged agent copy is corrupt"
printf '{"mod_jar":"%s","agent_jar":"%s","mod_version":"%s","mod_sha256":"%s","agent_sha256":"%s"}\n' \
  "$mod_name" "$agent_name" "$version" "$mod_sha" "$staged_agent_sha" > "$res/manifest.json" \
  || die "could not write manifest.json"

# Bundle-level provenance record: once the zip leaves this machine, the
# recipient can still verify WHICH CI executable is inside. Lives at the bundle
# root, not resources/ - resources/manifest.json is deny_unknown_fields by
# design and the launcher's resource verifier must keep rejecting strangers.
{
  echo "bundle: Cobblify-Windows-$version"
  echo "commit: $trusted_sha"
  echo "ci_run: $run_id"
  echo "exe: $EXE_SHIP_NAME"
  echo "exe_sha256: $exe_sha"
  echo "mod_jar: $mod_name"
  echo "mod_sha256: $mod_sha"
  echo "agent_jar: $agent_name"
  echo "agent_sha256: $staged_agent_sha"
} > "$stage/PROVENANCE.txt" || die "could not write PROVENANCE.txt"

# --- zip with an exact-entry assertion, then publish -------------------------
#
# The zip is built and VERIFIED under the trapped temp dir; only a fully
# verified archive is renamed into dist-owner/. A failed verification can
# never leave a distributable-looking file at the final name.

dist="$repo_root/dist-owner"
mkdir -p "$dist"
bundle="$dist/Cobblify-Windows-$version.zip"
rm -f "$bundle"
staged_zip="$tmpd/Cobblify-Windows-$version.zip"

publish_tmp="$dist/.Cobblify-Windows-$version.zip.tmp"
rm -f "$publish_tmp"
trap 'scrub_plaintext; rm -rf "$tmpd"; rm -f "$publish_tmp"' EXIT

( cd "$tmpd/stage" && zip -q -r -X -D "$staged_zip" "$BUNDLE_DIR_NAME" ) || die "zip failed"

zipinfo -1 "$staged_zip" | LC_ALL=C sort > "$tmpd/got_entries" \
  || die "could not list the bundle contents"
printf '%s\n' \
  "$BUNDLE_DIR_NAME/$EXE_SHIP_NAME" \
  "$BUNDLE_DIR_NAME/READ ME FIRST.txt" \
  "$BUNDLE_DIR_NAME/PROVENANCE.txt" \
  "$BUNDLE_DIR_NAME/resources/manifest.json" \
  "$BUNDLE_DIR_NAME/resources/$mod_name" \
  "$BUNDLE_DIR_NAME/resources/$agent_name" \
  | LC_ALL=C sort > "$tmpd/want_entries"
if ! cmp -s "$tmpd/got_entries" "$tmpd/want_entries"; then
  echo "error: the bundle does not hold exactly the expected paths" >&2
  diff "$tmpd/want_entries" "$tmpd/got_entries" >&2 || true
  exit 1
fi
# mv across filesystems is copy-then-unlink, not atomic - a failure could
# leave a partial file at the final name. Copy into dist-owner under a temp
# name (removed by the trap on any failure), then rename on the SAME
# filesystem, which is atomic.
cp "$staged_zip" "$publish_tmp" || die "could not stage the verified bundle for publication"
mv "$publish_tmp" "$bundle" || die "could not publish the verified bundle"
echo "bundle entries verified (6 paths, exact match)"

# --- done --------------------------------------------------------------------

echo
echo "windows bundle ready: $bundle"
echo "  exe:  run $run_id @ $trusted_sha  sha256=$exe_sha"
echo "  jars: built from this checkout, hashes in the manifest"
echo
echo "Record the exe sha256 in .ai/HANDOFF.md. Distribute PRIVATELY (DM) -"
echo "never attach to a public GitHub Release."
