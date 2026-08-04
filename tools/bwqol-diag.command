#!/bin/bash
# Cobblify (formerly BedwarsQOL) one-shot diagnostic collector (macOS).
# Read-only: inspects the Weave/Lunar/Cobblify install and writes ONE report
# file to the Desktop. Run it, then send bwqol-diag.txt back on Discord.
#
#   bash ~/Downloads/bwqol-diag.command
#
# Reference values below are from Jacob's known-good machine (Jul 30, 2026).

GOOD_JAR_SHA="97d91d866a00e490d538dd979e4cb61a4138b2de753738a2d7fe10e741de80dc"
GOOD_JAR_NAME="Cobblify-Lunar-0.8.1.jar (built Aug 4, 534052 bytes)"
# Forge counterpart (Prism instance mods folder), for reference:
GOOD_FORGE_JAR_SHA="29c13f6a0e77257cc4cf34d81a349ebac2f17c3162034a8d5d2d9562a1af2efa"
GOOD_FORGE_JAR_NAME="Cobblify-1.8.9-forge-0.8.1.jar"
GOOD_LOADER_SHA="e63da5ed3cc85868088527cd7d49ebd708785b6567da389fe89a89913ef4afd2"
GOOD_LOADER_NAME="Weave-Loader-Agent-1.3.3.jar"

OUT="${BWQOL_OUT:-$HOME/Desktop/bwqol-diag.txt}"
: > "$OUT" || { echo "Cannot write $OUT"; exit 1; }

JAR_SUMMARY="no Cobblify/BedwarsQOL jar found in ~/.weave/mods"
TOGGLE_SUMMARY="settings file not found"
LOADER_SUMMARY="no Weave loader jar found in ~/.weave"
NJARS=0

sec() { { echo; echo "======== $1 ========"; } >> "$OUT"; }

marker_count() { # jar, marker -> count of matches inside ChatNameTags.class
    unzip -p "$1" com/bedwarsqol/feature/ChatNameTags.class 2>/dev/null | grep -ac "$2"
}

analyze_jar() {
    local jar="$1" sha size verdict
    sha=$(shasum -a 256 "$jar" | awk '{print $1}')
    size=$(stat -f%z "$jar" 2>/dev/null)
    {
        echo "file:    $jar"
        echo "size:    $size bytes"
        echo "date:    $(stat -f '%Sm' "$jar" 2>/dev/null)"
        echo "sha256:  $sha"
    } >> "$OUT"

    if [ "$sha" = "$GOOD_JAR_SHA" ]; then
        verdict="CURRENT - byte-identical to Jacob's working build"
    elif ! unzip -l "$jar" 2>/dev/null | grep -q "weave.mod.json"; then
        verdict="WRONG BUILD - not a Weave mod (this looks like the Forge jar; Lunar needs the -Lunar jar)"
    elif ! unzip -l "$jar" 2>/dev/null | grep -q "com/bedwarsqol/feature/ChatNameTags.class"; then
        verdict="VERY OLD - no ChatNameTags class at all (0.2.0-era build)"
    elif [ "$(marker_count "$jar" rootTextField)" -ge 1 ] 2>/dev/null; then
        verdict="STALE - pre-Jul-2-18:09 build: has the exact chat-tag-after-name bug on Lunar. Replace with Jacob's jar."
    elif [ "$(marker_count "$jar" DECIDE_WINDOW_MS)" -eq 0 ] 2>/dev/null; then
        verdict="STALE - pre-Jul-2-01:19 build: missing the deferred-decision fix (random lines never get tagged). Replace with Jacob's jar."
    else
        verdict="UNKNOWN BUILD - internal markers look current but bytes differ from Jacob's jar. Replace with Jacob's exact jar to rule it out."
    fi
    echo "verdict: $verdict" >> "$OUT"
    # Keep the worst verdict as the summary (anything non-CURRENT wins).
    case "$JAR_SUMMARY" in
        CURRENT*|"no Cobblify/BedwarsQOL jar found"*) JAR_SUMMARY="$verdict" ;;
    esac
}

# -------- 1. System --------
sec "SYSTEM"
{
    date
    sw_vers 2>/dev/null
    echo "arch: $(uname -m)"
} >> "$OUT"

# -------- 2. Weave install --------
sec "WEAVE DIRECTORY (~/.weave)"
if [ -d "$HOME/.weave" ]; then
    ls -laR "$HOME/.weave/mods" "$HOME/.weave/mods-disabled" 2>/dev/null >> "$OUT"
    echo "-- all jars under ~/.weave --" >> "$OUT"
    find "$HOME/.weave" -type f -iname '*.jar' -exec shasum -a 256 {} \; >> "$OUT" 2>/dev/null
else
    echo "~/.weave DOES NOT EXIST" >> "$OUT"
fi

# Loader check
LOADER=$(find "$HOME/.weave" -maxdepth 1 -type f -iname 'Weave-Loader*.jar' 2>/dev/null | head -1)
if [ -n "$LOADER" ]; then
    LSHA=$(shasum -a 256 "$LOADER" | awk '{print $1}')
    if [ "$LSHA" = "$GOOD_LOADER_SHA" ]; then
        LOADER_SUMMARY="$(basename "$LOADER") - identical to Jacob's ($GOOD_LOADER_NAME)"
    else
        LOADER_SUMMARY="$(basename "$LOADER") - DIFFERS from Jacob's $GOOD_LOADER_NAME (sha $LSHA)"
    fi
fi

# -------- 3. Cobblify jar(s) --------
sec "COBBLIFY JAR ANALYSIS (~/.weave/mods)"
for jar in "$HOME/.weave/mods/"*[Cc]obblify*.jar "$HOME/.weave/mods/"*[Bb]edwars*.jar; do
    [ -f "$jar" ] || continue
    NJARS=$((NJARS + 1))
    [ $NJARS -gt 1 ] && echo "" >> "$OUT"
    analyze_jar "$jar"
done
if [ $NJARS -eq 0 ]; then
    echo "NO Cobblify (or old BedwarsQOL) jar in ~/.weave/mods -- the mod is not installed where Weave loads from." >> "$OUT"
elif [ $NJARS -gt 1 ]; then
    JAR_SUMMARY="MULTIPLE ($NJARS) Cobblify/BedwarsQOL jars in mods/ - old classes can win; keep exactly one. ($JAR_SUMMARY)"
fi

# -------- 4. Mod settings --------
sec "MOD SETTINGS (~/.cobblify/cobblify.json, falling back to ~/.bedwarsqol/bedwarsqol.json)"
CFG="$HOME/.cobblify/cobblify.json"
[ -f "$CFG" ] || CFG="$HOME/.bedwarsqol/bedwarsqol.json"
echo "settings file inspected: $CFG" >> "$OUT"
if [ -f "$CFG" ]; then
    cat "$CFG" >> "$OUT"
    ps_all=$(grep -o '"playerStats"[^,}]*' "$CFG" | head -1)
    ps_chat=$(grep -o '"playerStatsChat"[^,}]*' "$CFG" | head -1)
    ps_hover=$(grep -o '"playerStatsChatHover"[^,}]*' "$CFG" | head -1)
    {
        echo ""
        echo "-- chat-tag gates --"
        echo "  $ps_all   (master toggle)"
        echo "  $ps_chat   (the in-chat FKDR bracket)"
        echo "  $ps_hover   (the hover card)"
    } >> "$OUT"
    if echo "$ps_all" | grep -q true && echo "$ps_chat" | grep -q true; then
        TOGGLE_SUMMARY="chat FKDR bracket is ON (playerStats+playerStatsChat both true)"
    else
        TOGGLE_SUMMARY="chat FKDR bracket is OFF in settings! [$ps_all, $ps_chat] - turn on Hypixel Stats > Chat Stats"
    fi
else
    echo "settings file missing -- mod has never run (or wiped)" >> "$OUT"
fi
echo "-- stats cache --" >> "$OUT"
ls -la "$HOME/.cobblify/" 2>/dev/null >> "$OUT"
ls -la "$HOME/.bedwarsqol/" 2>/dev/null >> "$OUT"

# -------- 5. Weave loader logs --------
sec "WEAVE LOGS (~/.weave/logs)"
ls -lat "$HOME/.weave/logs" 2>/dev/null | head -8 >> "$OUT"
WLOG="$HOME/.weave/logs/latest.log"
if [ -f "$WLOG" ]; then
    {
        echo "-- ~/.weave/logs/latest.log (first 60 lines) --"
        head -60 "$WLOG"
        echo "-- discovery / errors --"
        grep -in "discovered\|error\|exception\|fail" "$WLOG" | head -20
    } >> "$OUT"
fi

# -------- 6. Lunar client logs --------
sec "LUNAR LOGS"
for lg in "$HOME/.lunarclient/profiles/"*/logs/latest.log; do
    [ -f "$lg" ] || continue
    {
        echo "-- $lg (modified $(stat -f '%Sm' "$lg")) --"
        head -30 "$lg"
        echo "... lines mentioning the mod or weave (max 60):"
        grep -in "bedwarsqol\|weave" "$lg" | head -60
        echo "... stack traces touching the mod (max 80 lines):"
        grep -n -B4 -A8 "at com\.bedwarsqol" "$lg" | head -80
        echo "... total lines containing Exception: $(grep -ci "exception" "$lg")"
    } >> "$OUT"
done

# -------- 7. Stray copies (Downloads/Desktop) --------
sec "STRAY COBBLIFY/BEDWARSQOL FILES (Downloads / Desktop)"
ls -la "$HOME/Downloads/"*[Cc]obblify* "$HOME/Desktop/"*[Cc]obblify* \
      "$HOME/Downloads/"*[Bb]edwars* "$HOME/Desktop/"*[Bb]edwars* 2>/dev/null >> "$OUT"
[ $? -ne 0 ] && echo "(none)" >> "$OUT"

# -------- Verdict --------
{
    echo
    echo "==================== VERDICT ===================="
    echo "Mod jar:      $JAR_SUMMARY"
    echo "Chat toggle:  $TOGGLE_SUMMARY"
    echo "Weave loader: $LOADER_SUMMARY"
    echo "Jars in mods: $NJARS"
    echo "Expected jar: $GOOD_JAR_NAME"
    echo "================================================="
} | tee -a "$OUT"

echo ""
echo ">>> Done. Send this file back on Discord:  $OUT"
open -R "$OUT" 2>/dev/null
