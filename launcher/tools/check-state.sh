#!/usr/bin/env bash
#
# Cobblify Launcher - inspect what the launcher did to this machine.
#
# READ ONLY. This script changes nothing; it reports. Run it after opening the
# launcher to verify the Phase 3 manual gates, and again after restoring the
# backup to confirm the uninstall path.

set -uo pipefail

LJ="$HOME/.lunarclient/settings/launcher.json"
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
info() { printf '        %s\n' "$1"; }

echo ""
echo "  Cobblify launcher state"
echo "  ───────────────────────"

# --- backup ------------------------------------------------------------------
if [ -f "$LJ.bak-cobblify" ]; then
  ok "backup exists (launcher.json.bak-cobblify)"
  if grep -q "javaagent" "$LJ.bak-cobblify" 2>/dev/null; then
    info "it already contained a javaagent - expected on this machine (June entry)"
  else
    info "it contains no javaagent - this was a clean machine before Cobblify"
  fi
else
  bad "no launcher.json.bak-cobblify - the launcher has not registered yet"
fi

# --- the live config ---------------------------------------------------------
python3 - "$LJ" <<'PY'
import json, sys, os
p = sys.argv[1]
try:
    d = json.load(open(p))
except Exception as e:
    print(f"  \033[31mFAIL\033[0m  launcher.json is not valid JSON: {e}")
    sys.exit(0)
print("  \033[32mPASS\033[0m  launcher.json is valid JSON")
s = d.get("settings", {})
for key in ("jvm-args", "jvmArgs"):
    v = s.get(key)
    if v is None:
        print(f"  \033[31mFAIL\033[0m  {key} is missing")
        continue
    n = v.count("-javaagent:")
    weave = "Weave-Loader-Agent" in v
    if n == 1 and weave:
        print(f"  \033[32mPASS\033[0m  {key}: exactly one javaagent, pointing at the Weave agent")
    elif n == 0:
        print(f"  \033[31mFAIL\033[0m  {key}: no javaagent (Cobblify will NOT load)")
    else:
        print(f"  \033[31mFAIL\033[0m  {key}: {n} javaagent entries - duplicated")
    # Surface any non-Cobblify args so a user's own JVM flags are seen to survive.
    others = [t for t in v.split() if "javaagent" not in t]
    if others:
        print(f"        other args preserved: {' '.join(others)}")
PY

# --- installed jars ----------------------------------------------------------
agent=$(find "$HOME/.weave" -maxdepth 1 -name 'Weave-Loader-Agent-*.jar' 2>/dev/null | wc -l | tr -d ' ')
mods=$(find "$HOME/.weave/mods" -maxdepth 1 -name 'Cobblify-Lunar-*.jar' 2>/dev/null | wc -l | tr -d ' ')
[ "$agent" = "1" ] && ok "one Weave agent in ~/.weave" || bad "$agent Weave agents in ~/.weave (expected 1)"
[ "$mods" = "1" ] && ok "one Cobblify jar in ~/.weave/mods" || bad "$mods Cobblify jars in ~/.weave/mods (expected 1 - more blocks launch)"

# --- did the mod actually load last game? ------------------------------------
newest=$(ls -t "$HOME/.weave/logs"/weave-loader-*.log 2>/dev/null | head -1)
if [ -n "$newest" ]; then
  if grep -q "bedwarsqol" "$newest" 2>/dev/null; then
    ok "last game loaded Cobblify  ($(basename "$newest"))"
  else
    bad "last game did NOT load Cobblify  ($(basename "$newest"))"
  fi
else
  info "no Weave logs yet - launch a game to test this"
fi

echo ""
echo "  To undo everything Cobblify changed in Lunar:"
echo "    cp \"$LJ.bak-cobblify\" \"$LJ\""
echo ""
