#!/bin/bash
#
# Tests fresh-mac.sh against a fake home and a fake /Applications in a temp
# dir. Nothing outside the temp dir is read or written; pgrep, lsregister,
# osascript and mv are stubs.
#
# Usage: launcher/tools/test-fresh-mac.sh
# Exit:  0 = every case passed

set -u
export LC_ALL=C

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
tool="$here/fresh-mac.sh"
T=$(mktemp -d) || exit 1
T=$(cd "$T" && pwd -P)
trap 'chmod -R u+w "$T" 2>/dev/null; command rm -rf "$T"' EXIT

pass=0 failed=0
ok()   { pass=$((pass + 1)); echo "  PASS  $*"; }
bad()  { failed=$((failed + 1)); echo "  FAIL  $*"; }
TAB=$(printf '\t')

# --- stubs ------------------------------------------------------------------
mkdir -p "$T/bin"
cat > "$T/bin/pgrep" <<'EOF'
#!/bin/bash
# Prints the "running" lines matching the pattern (last arg), like pgrep -fl.
pat=${!#}
[ -f "$FAKE_RUNNING" ] || exit 1
grep -F -- "$pat" "$FAKE_RUNNING" && exit 0
exit 1
EOF
cat > "$T/bin/lsregister" <<'EOF'
#!/bin/bash
echo "$*" >> "$FAKE_LSLOG"
EOF
cat > "$T/bin/osascript" <<'EOF'
#!/bin/bash
echo "Lunar Client"
EOF
# mv wrapper: FAKE_FAIL_DST (ERE on the destination) makes the move fail;
# FAKE_CRASH_DST does the move and then SIGKILLs the calling script, i.e. a crash
# between the journal's "intent" and "done".
cat > "$T/bin/mv" <<'EOF'
#!/bin/bash
src=$1 dst=$2
if [ -n "${FAKE_FAIL_DST:-}" ] && printf '%s' "$dst" | grep -Eq -- "$FAKE_FAIL_DST"; then
  echo "mv: injected failure" >&2; exit 1
fi
/bin/mv "$src" "$dst" || exit 1
if [ -n "${FAKE_CRASH_DST:-}" ] && printf '%s' "$dst" | grep -Eq -- "$FAKE_CRASH_DST"; then
  kill -9 "$PPID"
fi
EOF
chmod +x "$T/bin/"*

# --- fixture ----------------------------------------------------------------
HOME_="$T/home" APPS_="$T/Applications"

mkfile() { mkdir -p "$(dirname "$1")"; printf '%s\n' "${2:-$1}" > "$1"; }

populate() {   # populate [no-weave]
  command rm -rf "$T/home" "$T/Applications"
  mkdir -p "$HOME_" "$APPS_"
  local L="$HOME_/Library"
  mkfile "$HOME_/.lunarclient/settings/launcher.json" '{"settings":{"jvm-args":"-javaagent:/x"}}'
  mkfile "$HOME_/.lunarclient/settings/launcher.json.bak-cobblify" '{"settings":{}}'
  mkfile "$HOME_/.lunarclient/jre/zulu/bin/java" 'java'
  chmod 755 "$HOME_/.lunarclient/jre/zulu/bin/java"
  mkdir -p "$HOME_/.lunarclient/empty dir"
  ln -s settings/launcher.json "$HOME_/.lunarclient/link"
  [ "${1:-}" = no-weave ] || mkfile "$HOME_/.weave/mods/Cobblify-Lunar-0.17.0.jar" jar
  mkfile "$HOME_/.cobblify/launcher-preferences.json" '{"auto_join_hypixel":true}'
  chmod 600 "$HOME_/.cobblify/launcher-preferences.json"
  mkfile "$HOME_/.bedwarsqol/config.json"
  mkfile "$L/Application Support/lunarclient/state.json"
  [ "${1:-}" = no-weave ] || mkfile "$L/Application Support/minecraft/options.txt"
  mkfile "$L/Preferences/com.moonsworth.client.plist"
  mkfile "$L/Application Support/PrismLauncher/prismlauncher.cfg"
  mkfile "$L/Application Support/PrismLauncher/instances/My 1.8.9/mmc-pack.json"
  mkfile "$L/Caches/club.cobblify.launcher/WebKit/x"
  mkfile "$L/Logs/Lunar Client/launcher.log"
  mkfile "$L/Preferences/com.apple.unrelated.plist"
  mkfile "$HOME_/Documents/keep me.txt"
  mkfile "$APPS_/Lunar Client.app/Contents/Info.plist"
  mkfile "$APPS_/Prism Launcher.app/Contents/Info.plist"
  mkfile "$APPS_/Cobblify Launcher.app/Contents/Info.plist"
  mkfile "$APPS_/Safari.app/Contents/Info.plist"
}

# Paths, types, modes, sizes, link targets and hashes of both roots, minus the
# stash the tool creates.
tree() {
  ( cd "$T" && find home Applications -path home/CobblifyStash -prune -o -print0 \
      | xargs -0 stat -f "%N$TAB%HT$TAB%Lp$TAB%z$TAB%Y" | while IFS="$TAB" read -r p t m z y; do
        if [ "$t" = "Regular File" ]; then s=$(shasum -a 256 "$p" | cut -c1-64); else s=-; fi
        [ "$t" = Directory ] && z=-
        printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$p" "$t" "$m" "$z" "$y" "$s"
      done | sort )
}

run() {   # run <args...>  (env FAKE_* passes through)
  # The subshell swallows bash's "Killed: 9" notice for the crash cases.
  ( FRESH_HOME="$HOME_" FRESH_APPS="$APPS_" FRESH_PGREP="$T/bin/pgrep" \
    FRESH_LSREGISTER="$T/bin/lsregister" FRESH_OSASCRIPT="$T/bin/osascript" FRESH_MV="$T/bin/mv" \
    FAKE_RUNNING="$T/running" FAKE_LSLOG="$T/lslog" \
      "$tool" "$@" > "$T/out" 2>&1 ) 2>/dev/null
}

stash() { ls -d "$HOME_"/CobblifyStash/*/ 2>/dev/null | tail -1 | sed 's:/$::'; }

simulate_testing() {
  mkfile "$HOME_/.lunarclient/settings/launcher.json" 'fresh lunar'
  mkfile "$HOME_/.weave/mods/Cobblify-Lunar-0.17.0.jar" 'next jar'
  mkfile "$HOME_/.cobblify/launcher-last-seen" '0.18.0-next.1'
  mkfile "$HOME_/Library/Application Support/minecraft/options.txt" 'new'
  mkfile "$HOME_/Library/Caches/club.cobblify.launcher.next/x"
  mkfile "$HOME_/Library/WebKit/club.cobblify.launcher.next/y"
  mkfile "$HOME_/Applications/Cobblify Launcher (Next).app/Contents/Info.plist"
  mkfile "$APPS_/Lunar Client.app/Contents/Info.plist" 'fresh install'
}

expect_tree() { if [ "$(tree)" = "$1" ]; then ok "$2"; else bad "$2"; diff <(echo "$1") <(tree) | head -20; fi; }

# --- cases ------------------------------------------------------------------
echo "== status is read-only =="
populate; : > "$T/running"; before=$(tree)
run status && grep -q 'present  \[lunar\]' "$T/out" && ok "status lists present paths" || bad "status output"
expect_tree "$before" "status changes nothing"
[ ! -e "$HOME_/CobblifyStash" ] && ok "status makes no stash" || bad "status made a stash"

echo "== aside all, test, restore: exact round trip =="
populate; : > "$T/lslog"; before=$(tree)
run aside all --yes; rc=$?
[ $rc = 0 ] && ok "aside all succeeds" || { bad "aside all exit $rc"; cat "$T/out"; }
for p in .lunarclient .weave .cobblify .bedwarsqol "Library/Application Support/PrismLauncher" "Library/Logs/Lunar Client" "Library/Caches/club.cobblify.launcher"; do
  [ ! -e "$HOME_/$p" ] || bad "still present after aside: $p"
done
[ ! -e "$APPS_/Lunar Client.app" ] && [ -d "$(stash)/items/apps/Lunar Client.app.stashed" ] \
  && ok "apps stashed as .app.stashed" || bad "app stash layout"
grep -q -- "-u $APPS_/Lunar Client.app" "$T/lslog" && ok "lsregister -u before moving an app" || bad "no lsregister -u"
[ -e "$HOME_/Documents/keep me.txt" ] && [ -e "$HOME_/Library/Preferences/com.apple.unrelated.plist" ] && [ -e "$APPS_/Safari.app" ] \
  && ok "unrelated files untouched" || bad "unrelated files moved"
[ "$(cat "$(stash)/state")" = active ] && ok "stash is active" || bad "stash state"
simulate_testing
run restore --yes; rc=$?
[ $rc = 0 ] && ok "restore succeeds" || { bad "restore exit $rc"; cat "$T/out"; }
expect_tree "$before" "every original path is byte-identical after restore"
s=$(stash)
[ -f "$s/new/1/home/.cobblify/launcher-last-seen" ] && [ -d "$s/new/1/home/Applications/Cobblify Launcher (Next).app.stashed" ] \
  && [ -d "$s/new/1/home/Library/Caches/club.cobblify.launcher.next" ] && [ -d "$s/new/1/apps/Lunar Client.app.stashed" ] \
  && ok "test-created items are under new/" || { bad "new/ contents"; find "$s/new" -maxdepth 4; }
grep -q -- "-f $APPS_/Lunar Client.app" "$T/lslog" && ok "lsregister -f after returning an app" || bad "no lsregister -f"
[ "$(cat "$s/state")" = restored ] && ok "stash marked restored" || bad "stash state after restore"

echo "== paths absent before testing are swept (B1) =="
populate no-weave; before=$(tree)
run aside all --yes || bad "aside (no-weave) failed"
grep -q "absent${TAB}$HOME_/.weave" "$(stash)/inventory.tsv" && ok "absent paths recorded" || bad "absent not recorded"
simulate_testing
run restore --yes || { bad "restore (no-weave) failed"; cat "$T/out"; }
expect_tree "$before" "absent-before paths do not survive restore"
[ -d "$(stash)/new/1/home/.weave" ] && [ -d "$(stash)/new/1/home/Library/Application Support/minecraft" ] \
  && ok "created ~/.weave and minecraft are under new/" || bad "absent-before items not in new/"

echo "== --data-only keeps the apps =="
populate; before=$(tree)
run aside lunar --data-only --yes || bad "aside --data-only failed"
[ -e "$APPS_/Lunar Client.app" ] && [ ! -e "$HOME_/.lunarclient" ] && ok "Lunar app kept, data moved" || bad "data-only layout"
[ -e "$HOME_/Library/Application Support/PrismLauncher" ] && ok "prism untouched in lunar mode" || bad "prism moved in lunar mode"
run restore --yes || { bad "restore after data-only failed"; cat "$T/out"; }
expect_tree "$before" "data-only round trip"

echo "== refusals =="
populate; before=$(tree)
echo "123 /Applications/Lunar Client.app/Contents/MacOS/Lunar Client" > "$T/running"
run aside all --yes && bad "aside ran while Lunar runs" || ok "aside refuses while Lunar runs"
expect_tree "$before" "refused aside moved nothing"
echo "456 $HOME_/.lunarclient/jre/zulu/bin/java -cp x" > "$T/running"
run aside all --yes && bad "aside ran while the game JVM runs" || ok "aside refuses while the game JVM runs"
: > "$T/running"
run restore --yes && bad "restore without a stash" || ok "restore refuses with no active stash"
run aside all --yes || bad "aside failed"
run aside lunar --yes && bad "second aside allowed" || ok "a second aside is refused"
echo "789 /Users/me/Applications/Cobblify Launcher (Next).app/Contents/MacOS/cobblify-launcher" > "$T/running"
run restore --yes && bad "restore ran while Next runs" || ok "restore refuses while Next runs"
: > "$T/running"
run restore --yes || bad "restore failed"
expect_tree "$before" "round trip after refusals"

echo "== a failed aside puts everything back =="
populate; before=$(tree)
FAKE_FAIL_DST='items/home/\.lunarclient$' run aside all --yes && bad "aside should fail" || ok "aside fails on an injected move error"
expect_tree "$before" "failed aside is fully rolled back"
[ "$(cat "$(stash)/state")" = aborted ] && ok "stash not left active" || bad "stash state after rollback"
run aside all --yes && ok "a new aside is allowed after a rollback" || bad "aside after rollback refused"
run restore --yes || bad "restore failed"
expect_tree "$before" "round trip after a rolled-back aside"

echo "== a failed rollback leaves the stash active for restore =="
populate; before=$(tree)
FAKE_FAIL_DST="items/home/\\.lunarclient\$|^$HOME_/\\.cobblify\$" run aside all --yes && bad "aside should fail"
grep -q 'putting things back failed' "$T/out" && ok "aside reports the failed rollback" || { bad "rollback failure message"; cat "$T/out"; }
[ "$(cat "$(stash)/state")" = active ] && ok "stash stays active" || bad "stash state"
run restore --yes || { bad "restore after failed rollback"; cat "$T/out"; }
expect_tree "$before" "restore completes a failed rollback"

echo "== restore that fails partway can be retried (B2) =="
populate; before=$(tree)
run aside all --yes || bad "aside failed"
simulate_testing
FAKE_FAIL_DST="^$HOME_/\\.lunarclient\$" run restore --yes && bad "restore should fail"
[ -e "$HOME_/.cobblify/launcher-preferences.json" ] && ok "earlier originals were returned before the failure" || bad "nothing returned"
run restore --yes || { bad "retried restore failed"; cat "$T/out"; }
expect_tree "$before" "retried restore is exact"
! find "$(stash)/new" -path '*/.cobblify/launcher-preferences.json' | grep -q . \
  && ok "a returned original is never swept on retry" || bad "returned original was swept"

echo "== crash between intent and done, at every op =="
for c in "stash:items/home/\\.weave\$" "sweep:new/1/home/\\.cobblify\$" "return:^$HOME_/\\.lunarclient\$"; do
  op=${c%%:*} pat=${c#*:}
  populate; before=$(tree)
  if [ "$op" = stash ]; then
    FAKE_CRASH_DST="$pat" run aside all --yes
  else
    run aside all --yes || bad "aside failed"
    simulate_testing
    FAKE_CRASH_DST="$pat" run restore --yes
  fi
  awk -F'\t' '$3 == "intent" { i[$1] = 1 } $3 != "intent" { delete i[$1] } END { exit !length(i) }' "$(stash)/journal.tsv" \
    && ok "$op: crash left an intent without done" || bad "$op: crash did not interrupt a move"
  run restore --yes || { bad "$op: restore after crash failed"; cat "$T/out"; }
  expect_tree "$before" "$op: restore after a crash is exact"
done
! find "$(stash)/new" -path '*/.lunarclient/settings/launcher.json' -exec grep -q 'javaagent' {} \; -print | grep -q . \
  && ok "the crashed return was reconciled, not swept" || bad "crashed return was swept as test data"

echo "== a file changed while stashed is reported =="
populate
run aside all --yes || bad "aside failed"
printf 'tampered\n' > "$(stash)/items/home/.cobblify/launcher-preferences.json"
run restore --yes && bad "restore accepted a changed file" || ok "restore fails on a changed file"
grep -q 'CHANGED.*\.cobblify' "$T/out" && ok "the changed path is named" || { bad "changed path not named"; cat "$T/out"; }
[ "$(cat "$(stash)/state")" = active ] && ok "stash stays active after a mismatch" || bad "stash state after mismatch"

echo "== the tool never deletes user data =="
if grep -nE '(^|[^[:alnum:]_])rm ' "$tool" | grep -vE 'command rm -f -- "\$(cover|plan|sweep|swept|tmp_hashes)"' | grep -q .; then
  bad "fresh-mac.sh has an rm outside its own temp files:"; grep -nE '(^|[^[:alnum:]_])rm ' "$tool"
else
  ok "only its own mktemp files are ever removed"
fi

echo
echo "$pass passed, $failed failed"
[ "$failed" = 0 ]
