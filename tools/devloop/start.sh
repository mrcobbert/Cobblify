#!/bin/bash
# Cobblify UI dev client. Run this from YOUR macOS account (not `agent`) in a terminal pane and
# leave it open. It starts Minecraft 1.8.9 + Forge with the code an agent staged in
# /Users/Shared/cobblify-dev, and starts it again whenever the agent asks for a restart.
#
#   start.sh             offline dev account; fine for UI work (no Hypixel)
#   start.sh --hypixel   log in with your Microsoft account through DevAuth (first run asks you
#                        to approve a login code in your browser; the token stays in ~/.devauth)
#
# Agents drive the game through a control API on 127.0.0.1:47821. It only opens and clicks
# Cobblify screens, and turns input off while you are on a multiplayer server.
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

trap 'exit 130' INT
while :; do
  rm -f "$RUN/restart-requested"
  R=$(cd "$D/current" 2>/dev/null && pwd -P) || {
    echo "Nothing is staged yet. Ask the agent to run: cobdev stage"
    exit 1
  }
  CP=$(paste -sd: "$R/launch/classpath.txt")
  JVM=()
  while IFS= read -r line; do
    [ -n "$line" ] && JVM+=("$line")
  done < "$R/launch/jvm.args"
  echo "== Cobblify dev client: $(grep -E '"(branch|commit|worktree)"' "$R/launch/source.json" | tr -d ' ",' | tr '\n' ' ')"
  (
    cd "$RUN" && exec "$D/jdk/bin/java" -Xms512M -Xmx2G \
      -javaagent:"$D/devagent.jar" \
      -Dcobdev.port=$PORT -Dcobdev.run="$RUN" -Dcobdev.classes="$(cat "$R/launch/classes.dir")" \
      -Ddevauth.enabled=$DEVAUTH \
      "${JVM[@]}" -cp "$CP" "$(cat "$R/launch/main.txt")"
  )
  code=$?
  if [ ! -f "$RUN/restart-requested" ]; then
    echo "== Game exited (code $code). Run this script again to start it."
    exit $code
  fi
  echo "== Restart requested by the agent; starting again with the newly staged code."
  sleep 1
done
