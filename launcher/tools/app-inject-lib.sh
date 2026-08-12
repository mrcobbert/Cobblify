# Injection of the token-baked jars into a built Cobblify Launcher .app.
#
# Sourced by tools/package-owner-bundle.sh (the real owner run) and by
# launcher/tools/test-injection.sh, which drives these same functions with fake
# jars and no token. Nothing here reads the backend url/token - the jars arrive
# already baked - so the test exercises the real code path rather than a
# re-implementation of it.
#
# Not executable and has no side effects on source. Every function is
# fail-closed and calls die() on the first problem.

command -v die >/dev/null 2>&1 \
  || { echo "app-inject-lib.sh: the sourcing script must define die()" >&2; exit 1; }

# Where the launcher is expected to be built. Tauri writes this path; the owner
# script only ever reads it, and copies it before touching anything.
COBBLIFY_APP_NAME="Cobblify Launcher.app"
COBBLIFY_APP_BUILD_DIR="launcher/src-tauri/target/release/bundle/macos"

# The resources path inside the .app, relative to the bundle root.
#
# MEASURED 2026-08-04, not assumed: a real `npm run tauri build` put
# `bundle.resources` at Contents/Resources/resources/, and running that bundle
# with HOME redirected to a temp dir installed the staged jars - so the path the
# Rust side derives (`resource_dir().join("resources")`, i.e.
# Contents/MacOS/../Resources/resources) is the same directory this script
# writes into. Both halves verified; do not change one without the other.
COBBLIFY_APP_RESOURCES="Contents/Resources/resources"

# Every file a freshly built, BLANK launcher bundle contains, relative to the
# .app root. Deliberately a literal list rather than a scan of the build output:
# the bundle manifest assertion compares against this, and a list generated from
# the tree being checked would accept whatever it found.
#
# If Tauri's bundle layout changes (a Frameworks/ dir, a helper binary, an extra
# icon), packaging FAILS and this list must be updated by hand after inspecting
# what changed. That is the intended behaviour.
cobblify_blank_app_files() {
  cat <<'EOF'
Contents/Info.plist
Contents/MacOS/cobblify-launcher
Contents/Resources/icon.icns
Contents/Resources/resources/.gitkeep
EOF
}

# The same list after injection and ad-hoc signing: the three injected resource
# files, plus the seal codesign writes.
# Usage: cobblify_injected_app_files <mod jar name> <agent jar name>
cobblify_injected_app_files() {
  local mod_jar=$1 agent_jar=$2
  cobblify_blank_app_files
  printf '%s\n' \
    "$COBBLIFY_APP_RESOURCES/manifest.json" \
    "$COBBLIFY_APP_RESOURCES/$mod_jar" \
    "$COBBLIFY_APP_RESOURCES/$agent_jar" \
    "Contents/_CodeSignature/CodeResources"
}

# The zip entry names those files get, i.e. each one prefixed with the .app
# directory as it is stored in the archive.
# Usage: cobblify_app_zip_entries <app dir name> <mod jar name> <agent jar name>
cobblify_app_zip_entries() {
  local app_name=$1
  cobblify_injected_app_files "$2" "$3" | sed "s|^|$app_name/|"
}

# A launcher build must be blank: the jars are injected into a COPY at package
# time and must never be committed, built into the app, or left in the checked
# out resources directory. Anything already in there means the build is not the
# artifact this script thinks it is.
# Usage: cobblify_assert_blank_app <app path>
cobblify_assert_blank_app() {
  local app=$1 res="$1/$COBBLIFY_APP_RESOURCES" found
  [ -d "$app" ] || die "no launcher bundle at $app"
  [ -d "$res" ] || die "$app has no $COBBLIFY_APP_RESOURCES - is this a Cobblify Launcher build?"
  found=$(find "$res" -type f \( -name '*.jar' -o -name 'manifest.json' \) -print)
  [ -z "$found" ] || die "the launcher build at $app is NOT blank - it already contains:
$found
Delete launcher/src-tauri/resources/*.jar and rebuild."
}

# Writes manifest.json and copies both jars into a STAGED bundle.
#
# The manifest shape is fixed by the Rust side (launcher/src-tauri/src/resources.rs,
# serde with deny_unknown_fields): exactly mod_jar, agent_jar, mod_version,
# mod_sha256, agent_sha256, all snake_case. An extra or renamed key makes the
# launcher refuse to start, by design.
#
# Usage: cobblify_inject_app <staged app> <mod jar path> <agent jar path> <version>
cobblify_inject_app() {
  local app=$1 mod_src=$2 agent_src=$3 version=$4
  local res="$app/$COBBLIFY_APP_RESOURCES"
  local mod_name agent_name mod_sha agent_sha

  [ -d "$res" ] || die "$res does not exist in the staged bundle"
  [ -f "$mod_src" ] || die "mod jar not found: $mod_src"
  [ -f "$agent_src" ] || die "agent jar not found: $agent_src"

  mod_name=$(basename "$mod_src")
  agent_name=$(basename "$agent_src")

  # The names and version go into JSON unescaped, so refuse anything that would
  # need escaping instead of silently producing a broken manifest.
  printf '%s' "$version" | grep -Eq '^[0-9A-Za-z._+-]+$' \
    || die "version '$version' is not safe to write into manifest.json"
  printf '%s' "$mod_name" | grep -Eq '^[0-9A-Za-z._+-]+\.jar$' \
    || die "mod jar name '$mod_name' is not safe to write into manifest.json"
  printf '%s' "$agent_name" | grep -Eq '^[0-9A-Za-z._+-]+\.jar$' \
    || die "agent jar name '$agent_name' is not safe to write into manifest.json"

  cp "$mod_src" "$res/$mod_name" || die "could not stage $mod_name"
  cp "$agent_src" "$res/$agent_name" || die "could not stage $agent_name"

  # Hash the STAGED copies, not the sources: the manifest must describe the
  # bytes that ship, so a truncated copy is caught here rather than by a friend.
  mod_sha=$(shasum -a 256 "$res/$mod_name" | awk '{print $1}')
  agent_sha=$(shasum -a 256 "$res/$agent_name" | awk '{print $1}')
  [ -n "$mod_sha" ] && [ -n "$agent_sha" ] || die "could not hash the staged jars"

  printf '{"mod_jar":"%s","agent_jar":"%s","mod_version":"%s","mod_sha256":"%s","agent_sha256":"%s"}\n' \
    "$mod_name" "$agent_name" "$version" "$mod_sha" "$agent_sha" > "$res/manifest.json" \
    || die "could not write manifest.json"

  # Read back what shipped: the launcher rejects an unexpected jar, so an extra
  # file here would be a bundle that fails on the friend's machine.
  local jars
  jars=$(find "$res" -maxdepth 1 -type f -name '*.jar' | wc -l | tr -d '[:space:]')
  [ "$jars" = "2" ] || die "staged resources hold $jars jar(s), expected exactly 2"
  grep -q "\"mod_sha256\":\"$mod_sha\"" "$res/manifest.json" \
    || die "manifest.json does not describe the staged mod jar"
  grep -q "\"agent_sha256\":\"$agent_sha\"" "$res/manifest.json" \
    || die "manifest.json does not describe the staged agent jar"
}

# Ad-hoc signs a staged bundle and then VALIDATES the signature.
#
# Order matters: injecting files invalidates any signature the bundle already
# carried, so this must run after cobblify_inject_app, never before.
#
# `codesign -dv` only DISPLAYS a signature and would happily report one on a
# bundle whose files no longer match their sealed hashes; --verify --deep
# --strict is the check that actually re-hashes the contents.
# Usage: cobblify_sign_app <staged app>
cobblify_sign_app() {
  local app=$1
  codesign --force --deep --sign - "$app" \
    || die "ad-hoc codesign failed for $app"
  codesign --verify --deep --strict "$app" \
    || die "codesign --verify --deep --strict failed for $app - refusing to ship an invalid bundle"
}
