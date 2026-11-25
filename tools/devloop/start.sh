#!/bin/bash
# Cobblify dev client. Run this from YOUR macOS account (not `agent`) in a terminal pane and
# leave it open. It starts Minecraft 1.8.9 (Ornithe: Fabric Loader + Pylon on Java 25) with the
# code an agent staged in /Users/Shared/cobblify-dev, and starts it again whenever the agent asks
# for a restart - including switches between dev mode and prod mode (the release jar).
#
#   start.sh             offline dev account; fine for UI work (no Hypixel)
#   start.sh --hypixel   log in with your Microsoft account through DevAuth (first run asks you
#                        to approve a login code in your browser; the token stays in ~/.devauth)
#
# Agents drive the game through a control API on 127.0.0.1:47821. It only opens and clicks
# Cobblify screens, and turns input off while you are on a multiplayer server.
#
# Everything about how to launch (java binary, JVM flags, classpath, main class, mode) comes from
# the staged runtime, so this script does not change when the launch does.
set -u

D=/Users/Shared/cobblify-dev
RUN="$HOME/Library/Application Support/cobblify-dev"
PORT=47821
DEVAUTH=false
[ "${1:-}" = "--hypixel" ] && DEVAUTH=true

if [ "$(id -un)" = "agent" ]; then
  echo "Run this from your own account, not agent (agent cannot open windows)."
  exit 1
fi
if (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; then
  echo "A dev client is already running (port $PORT is taken)."
  exit 1
fi

mkdir -p "$RUN"
if [ ! -f "$RUN/options.txt" ]; then
  # Keep rendering and skip the pause menu when the window is in the background; start muted.
  printf 'pauseOnLostFocus:false\nguiScale:2\nfboEnable:true\nsoundCategory_master:0.0\n' > "$RUN/options.txt"
fi

lines() {  # file -> array ITEMS (one element per non-empty line)
  ITEMS=()
  [ -f "$1" ] || return 0
  while IFS= read -r line; do
    [ -n "$line" ] && ITEMS+=("$line")
  done < "$1"
}

trap 'exit 130' INT
while :; do
  rm -f "$RUN/restart-requested"
  R=$(cd "$D/current" 2>/dev/null && pwd -P) || {
    echo "Nothing is staged yet. Ask the agent to run: cobdev stage"
    exit 1
  }
  L="$R/launch"
  CP=$(paste -sd: "$L/classpath.txt")
  lines "$L/jvm.args"; JVM=("${ITEMS[@]}")
  echo "== Cobblify dev client: $(grep -E '"(branch|commit|worktree|mode)"' "$L/source.json" | tr -d ' ",' | tr '\n' ' ')"
  if [ -f "$L/java.txt" ]; then
    # Ornithe runtime: everything comes from the runtime, including the agent jar and the mode.
    JAVA=$(cat "$L/java.txt")
    lines "$L/args.txt"; ARGS=(${ITEMS[@]+"${ITEMS[@]}"})
    MODS=$(cat "$L/addmods.txt" 2>/dev/null)
    if $DEVAUTH && [ -f "$L/devauth.txt" ]; then
      MODS="$MODS:$(cat "$L/devauth.txt")"
    fi
    (
      cd "$RUN" && exec "$JAVA" \
        -Dcobdev.port=$PORT -Dcobdev.run="$RUN" -Dcobdev.classes="$(cat "$L/classes.dir")" \
        -Ddevauth.enabled=$DEVAUTH -Dfabric.addMods="$MODS" \
        "${JVM[@]}" -cp "$CP" "$(cat "$L/main.txt")" ${ARGS[@]+"${ARGS[@]}"}
    )
  else
    # Forge-era runtime (staged by an older cobdev): Intel Java 8 and the old agent.
    (
      cd "$RUN" && exec "$D/jdk/bin/java" -Xms512M -Xmx2G \
        -javaagent:"$D/devagent.jar" \
        -Dcobdev.port=$PORT -Dcobdev.run="$RUN" -Dcobdev.classes="$(cat "$L/classes.dir")" \
        -Ddevauth.enabled=$DEVAUTH \
        "${JVM[@]}" -cp "$CP" "$(cat "$L/main.txt")"
    )
  fi
  code=$?
  if [ ! -f "$RUN/restart-requested" ]; then
    echo "== Game exited (code $code). Run this script again to start it."
    exit $code
  fi
  echo "== Restart requested by the agent; starting again with the newly staged code."
  sleep 1
done
