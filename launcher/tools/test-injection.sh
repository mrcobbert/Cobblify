#!/usr/bin/env bash
#
# Proves the launcher packaging path WITHOUT any secret.
#
# tools/package-owner-bundle.sh bakes the real backend url and token into jars
# and must never be run to test packaging logic. This script drives the same
# functions - it sources launcher/tools/app-inject-lib.sh, the one the owner
# script sources - against FAKE jars it creates in a temp directory. No token,
# no Gradle build, no dist-owner/, nothing written outside $tmpd.
#
# It needs one real artifact: a built, blank Cobblify Launcher.app, because the
# expected-layout list and the Rust-side resource lookup are only meaningful
# against a real bundle. Build it with:
#   cd launcher && npm run tauri build -- --bundles app
#
# Usage: launcher/tools/test-injection.sh
# Exit:  0 = every case behaved as expected
#        1 = a case failed (details on stderr)

set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
. "$repo_root/launcher/tools/app-inject-lib.sh"

# `pwd -P` matters, it is not tidiness. mktemp -d returns a path under
# /var/folders/..., and /var is a symlink to /private/var. tauri-utils refuses
# any current_exe() with a symlinked ancestor on macOS (starting_binary.rs, the
# process-relaunch-dangerous-allow-symlink-macos guard), so resource_dir()
# returns Err(UnknownPath) and stage 8 below would fail for a reason that has
# nothing to do with packaging. MEASURED 2026-08-04: the identical bundle
# installs from /private/tmp/... and errors from /var/folders/....
tmpd=$(mktemp -d) || die "mktemp failed"
tmpd=$(cd "$tmpd" && pwd -P) || die "could not resolve the temp directory"
chmod 700 "$tmpd"
trap 'rm -rf "$tmpd"' EXIT

pass=0
ok()   { pass=$((pass + 1)); echo "  PASS  $*"; }
fail() { echo "  FAIL  $*" >&2; exit 1; }

# A case that must FAIL runs in a subshell, because die() exits.
refuses() { # <description> <command...>
  local what=$1; shift
  if ( "$@" ) >/dev/null 2>&1; then
    fail "$what - it was ACCEPTED"
  fi
  ok "$what"
}

app_src="$repo_root/$COBBLIFY_APP_BUILD_DIR/$COBBLIFY_APP_NAME"
[ -d "$app_src" ] || die "no launcher build at $app_src
Build it first: cd \"$repo_root/launcher\" && npm run tauri build -- --bundles app"

version="9.9.9-test"
mod_jar="$tmpd/Cobblify-Lunar-$version.jar"
agent_jar="$tmpd/Weave-Loader-Agent-1.3.3.jar"
forge_jar="$tmpd/Cobblify-1.8.9-forge-$version.jar"
printf 'fake mod jar, not a real build\n'   > "$mod_jar"
printf 'fake weave agent, not a real one\n' > "$agent_jar"
printf 'fake forge jar, not a real build\n' > "$forge_jar"

echo "== 1. the build output is blank =="
cobblify_assert_blank_app "$app_src"
ok "a freshly built .app carries no jar and no manifest"

echo "== 2. stage, inject, sign =="
stage="$tmpd/stage"
mkdir "$stage"
staged_app="$stage/$COBBLIFY_APP_NAME"
cp -R "$app_src" "$stage/"
cobblify_inject_app "$staged_app" "$mod_jar" "$agent_jar" "$version" "$forge_jar"
ok "injection wrote all three jars and manifest.json"

res="$staged_app/$COBBLIFY_APP_RESOURCES"
manifest=$(cat "$res/manifest.json")
for key in mod_jar agent_jar mod_version mod_sha256 agent_sha256 forge_jar forge_sha256; do
  case "$manifest" in
    *"\"$key\":"*) ;;
    *) fail "manifest.json has no $key" ;;
  esac
done
# deny_unknown_fields on the Rust side: an extra key is a hard failure there, so
# the count must be exactly seven. forge_jar/forge_sha256 are optional there ONLY
# so a bundle built before Forge support still runs; the writer always emits both,
# and half a pair is a hard failure on both sides.
keys=$(printf '%s' "$manifest" | tr ',' '\n' | grep -c '":' || true)
[ "$keys" = "7" ] || fail "manifest.json has $keys keys, expected exactly 7"
ok "manifest.json has exactly the 7 fields resources.rs deserialises"

expect_mod=$(shasum -a 256 "$mod_jar" | awk '{print $1}')
case "$manifest" in
  *"$expect_mod"*) ok "manifest records the real sha256 of the injected mod jar" ;;
  *) fail "manifest sha256 does not match the injected mod jar" ;;
esac

echo "== 3. the original build output was not mutated =="
cobblify_assert_blank_app "$app_src"
ok "injection happened only in the staging copy"

echo "== 4. signing and validation =="
cobblify_sign_app "$staged_app"
ok "ad-hoc signed after injection, and codesign --verify --deep --strict passed"

# Positive control: the verification must actually detect tampering, otherwise
# passing it proves nothing. `codesign -dv` would still report a signature here.
tamper="$tmpd/tampered"
mkdir "$tamper"
cp -R "$staged_app" "$tamper/"
printf 'x' >> "$tamper/$COBBLIFY_APP_NAME/$COBBLIFY_APP_RESOURCES/manifest.json"
if codesign --verify --deep --strict "$tamper/$COBBLIFY_APP_NAME" >/dev/null 2>&1; then
  fail "a file changed after signing still verified - the check proves nothing"
fi
ok "control: a file changed after signing FAILS --verify --deep --strict"

echo "== 5. the bundle holds exactly the expected paths =="
installer_cmd="$repo_root/lunar/dist/Install BedwarsQOL (Lunar).command"
installer_bat="$repo_root/lunar/dist/Install BedwarsQOL (Lunar).bat"
[ -f "$installer_cmd" ] || die "missing $installer_cmd"
[ -f "$installer_bat" ] || die "missing $installer_bat"
cp "$mod_jar" "$agent_jar" "$installer_cmd" "$installer_bat" "$stage/"

bundle="$tmpd/bundle.zip"
zip_stage() { # rebuilds the archive from whatever is in $stage right now
  rm -f "$bundle"
  ( cd "$stage" && zip -q -r -X -y -D "$bundle" \
      "$COBBLIFY_APP_NAME" \
      "$(basename "$agent_jar")" \
      "$(basename "$mod_jar")" \
      "Install BedwarsQOL (Lunar).command" \
      "Install BedwarsQOL (Lunar).bat" ) || die "zip failed"
}
want_entries() {
  {
    printf '%s\n' \
      "$(basename "$agent_jar")" \
      "$(basename "$mod_jar")" \
      "Install BedwarsQOL (Lunar).command" \
      "Install BedwarsQOL (Lunar).bat"
    cobblify_app_zip_entries "$COBBLIFY_APP_NAME" \
      "$(basename "$mod_jar")" "$(basename "$agent_jar")" "$(basename "$forge_jar")"
  } | LC_ALL=C sort
}

zip_stage
zipinfo -1 "$bundle" | LC_ALL=C sort > "$tmpd/got"
want_entries > "$tmpd/want"
if ! cmp -s "$tmpd/got" "$tmpd/want"; then
  echo "  (< expected, > in the zip)" >&2
  diff "$tmpd/want" "$tmpd/got" >&2 || true
  fail "the archive listing does not match the independently built expectation"
fi
ok "archive listing matches the expected paths exactly ($(wc -l < "$tmpd/want" | tr -d '[:space:]') of them)"

# Positive control for that comparison: an unexpected file must break it. This
# is the case the old "exactly 4 entries" count could not catch once the .app
# made the entry count large and variable.
printf 'stowaway' > "$stage/$COBBLIFY_APP_NAME/Contents/Resources/EXTRA.txt"
zip_stage
zipinfo -1 "$bundle" | LC_ALL=C sort > "$tmpd/got"
if cmp -s "$tmpd/got" "$tmpd/want"; then
  fail "an unexpected file inside the .app was accepted by the comparison"
fi
ok "control: one unexpected file inside the .app FAILS the comparison"
rm "$stage/$COBBLIFY_APP_NAME/Contents/Resources/EXTRA.txt"
zip_stage

echo "== 6. the archive survives a round trip =="
out="$tmpd/unzipped"
mkdir "$out"
( cd "$out" && unzip -q "$bundle" ) || die "unzip failed"
[ -x "$out/$COBBLIFY_APP_NAME/Contents/MacOS/cobblify-launcher" ] \
  || fail "the launcher binary lost its executable bit through the archive"
ok "the launcher binary is still executable after unzip"
codesign --verify --deep --strict "$out/$COBBLIFY_APP_NAME" \
  || fail "the signature did not survive the archive round trip"
ok "codesign --verify --deep --strict passes on the extracted .app"

echo "== 7. fail-closed guards =="
dirty="$tmpd/dirty"
mkdir "$dirty"
cp -R "$app_src" "$dirty/"
printf 'x' > "$dirty/$COBBLIFY_APP_NAME/$COBBLIFY_APP_RESOURCES/Sneaky.jar"
refuses "a build with a jar already inside it is refused" \
  cobblify_assert_blank_app "$dirty/$COBBLIFY_APP_NAME"

refuses "a missing mod jar is refused" \
  cobblify_inject_app "$staged_app" "$tmpd/does-not-exist.jar" "$agent_jar" "$version"
refuses "a version that would break the JSON is refused" \
  cobblify_inject_app "$staged_app" "$mod_jar" "$agent_jar" 'ver"sion'

echo "== 8. the real Rust code reads what was injected =="
# The strongest check available without shipping anything: run the injected
# bundle with HOME redirected into $tmpd. If manifest.json parses under
# deny_unknown_fields, every hash matches, and resource_dir().join("resources")
# resolves to the directory this script wrote, the launcher installs the fake
# jars under the fake HOME. Nothing touches the real ~/.weave or ~/.lunarclient,
# and Lunar Client is never started.
#
# The fake home now needs a Lunar FIXTURE. Lunar setup is gated on
# ~/.lunarclient/settings/launcher.json existing, because an absent Lunar must be
# reported as "not installed" rather than as an app-wide error - that is the
# whole point of the Forge work. Without this fixture the launcher would
# correctly skip the Lunar install and the assertions below would be proving
# nothing.
fake_home="$tmpd/home"
mkdir -p "$fake_home/.lunarclient/settings"
printf '{"settings":{"jvm-args":"","jvmArgs":""}}\n' \
  > "$fake_home/.lunarclient/settings/launcher.json"
HOME="$fake_home" "$out/$COBBLIFY_APP_NAME/Contents/MacOS/cobblify-launcher" \
  >"$tmpd/app.log" 2>&1 &
app_pid=$!
sleep 6
kill "$app_pid" 2>/dev/null || true
wait "$app_pid" 2>/dev/null || true

installed_mod="$fake_home/.weave/mods/$(basename "$mod_jar")"
installed_agent="$fake_home/.weave/$(basename "$agent_jar")"
[ -f "$installed_mod" ] || fail "the launcher did not install the mod jar (see $tmpd/app.log)"
[ -f "$installed_agent" ] || fail "the launcher did not install the agent jar"
cmp -s "$installed_mod" "$mod_jar" || fail "the installed mod jar differs from the injected one"
cmp -s "$installed_agent" "$agent_jar" || fail "the installed agent jar differs from the injected one"
ok "the running .app found, verified and installed both Lunar jars"

# The Forge jar must verify (a bad one would have aborted startup and taken the
# Lunar install with it) but must NOT be installed: nothing was chosen, and the
# launcher never installs into a Prism instance on the user's behalf.
stray=$(find "$fake_home" -type f -name "$(basename "$forge_jar")" -print)
[ -z "$stray" ] || fail "the launcher installed the Forge jar without a choice being made:
$stray"
ok "the Forge jar was verified but NOT installed speculatively"

echo "== 9. test-drive.command uses local build outputs before Desktop staging =="
test_drive="$repo_root/launcher/tools/test-drive.command"
[ -f "$test_drive" ] || die "missing $test_drive"

grep -q 'lunar/build.gradle.kts' "$test_drive" \
  || fail "test-drive.command does not read version from lunar/build.gradle.kts"
grep -q 'lunar/build/libs/Cobblify-Lunar-' "$test_drive" \
  || fail "test-drive.command does not derive mod_src from lunar/build/libs"
grep -q 'versions/1.8.9-forge/build/libs/Cobblify-1.8.9-forge-' "$test_drive" \
  || fail "test-drive.command does not derive forge_src from versions/1.8.9-forge/build/libs"
grep -q 'Weave-Loader-Agent-' "$test_drive" \
  || fail "test-drive.command does not locate the installed Weave agent"
if grep -q '\.weave/mods' "$test_drive"; then
  fail "test-drive.command still reads the Lunar mod from ~/.weave/mods"
fi

inject_line=$(grep -n 'cobblify_inject_app ' "$test_drive" | head -1)
[ -n "$inject_line" ] || fail "test-drive.command has no cobblify_inject_app call"
inject_body=${inject_line#*:}
for arg in '"$mod_src"' '"$agent_src"' '"$version"' '"$forge_src"'; do
  case "$inject_body" in
    *"$arg"*) ;;
    *) fail "test-drive.command cobblify_inject_app omits argument $arg" ;;
  esac
done

desktop_rm=$(grep -n 'rm -rf "\$dest"' "$test_drive" | head -1 | cut -d: -f1)
[ -n "$desktop_rm" ] || fail "test-drive.command has no Desktop staging step"

must_precede_desktop() { # <description> <grep pattern>
  local what=$1 pattern=$2 line
  line=$(grep -n "$pattern" "$test_drive" | head -1 | cut -d: -f1)
  [ -n "$line" ] || fail "test-drive.command has no $what"
  if [ "$line" -ge "$desktop_rm" ]; then
    fail "test-drive.command $what too late (line $line, Desktop rm at $desktop_rm)"
  fi
}

must_precede_desktop 'version read' 'could not read version from lunar/build.gradle.kts'
must_precede_desktop 'mod_src assignment' 'mod_src="\$repo_root/lunar/build/libs/Cobblify-Lunar-\$version.jar"'
must_precede_desktop 'forge_src assignment' 'forge_src="\$repo_root/versions/1.8.9-forge/build/libs/Cobblify-1.8.9-forge-\$version.jar"'
must_precede_desktop 'agent check' '\[ -n "\$agent_src" \]'
must_precede_desktop 'Lunar jar check' '\[ -f "\$mod_src" \]'
must_precede_desktop 'Forge jar check' '\[ -f "\$forge_src" \]'

ok "test-drive.command selects local Lunar/Forge build outputs, verifies all inputs, and passes forge to cobblify_inject_app before Desktop staging"

echo
echo "all $pass checks passed - injection, manifest, signing, verification and"
echo "the archive listing are proven with fake jars and no token."
