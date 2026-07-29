#!/bin/bash

set -euo pipefail

NODE_VERSION="v22.23.0"
REPO_TARBALL="https://github.com/mrcobbert/Cobblify/archive/refs/heads/main.tar.gz"
WORKDIR="$HOME/.bedwarsqol-setup"

say()  { printf '\n\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m%s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mSetup failed:\033[0m %s\n\nAsk in the Discord for help and copy the red text above.\n' "$*" >&2; exit 1; }

trap 'die "unexpected error on line $LINENO"' ERR

# Pixel-art banner (45 cols wide — safe in an 80-col terminal)
banner() {
  printf '\n'
  printf '\033[38;2;235;235;235m ███   ███  ████  ████  █     ███ █████ █   █\033[0m\n'
  printf '\033[38;2;205;205;205m█     █   █ █   █ █   █ █      █  █      █ █ \033[0m\n'
  printf '\033[38;2;175;175;175m█     █   █ ████  ████  █      █  ████    █  \033[0m\n'
  printf '\033[38;2;145;145;145m█     █   █ █   █ █   █ █      █  █       █  \033[0m\n'
  printf '\033[38;2;115;115;115m ███   ███  ████  ████  █████ ███ █       █  \033[0m\n'
  printf '\n'
  printf '\033[38;2;100;100;100m────────\033[38;2;190;190;190m  Created by MrCobbert  \033[38;2;100;100;100m────────\033[0m\n'
  printf '\n'
}

banner

# 1. Pick the right Node build for this Mac
case "$(uname -m)" in
  arm64)  NODE_PLAT="darwin-arm64" ;;
  x86_64) NODE_PLAT="darwin-x64" ;;
  *)      die "unsupported CPU: $(uname -m)" ;;
esac

mkdir -p "$WORKDIR"
cd "$WORKDIR"

NODE_DIR="$WORKDIR/node-${NODE_VERSION}-${NODE_PLAT}"
if [[ ! -x "$NODE_DIR/bin/node" ]]; then
  say "Downloading Node.js..."
  curl -fL# -o node.tar.gz "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-${NODE_PLAT}.tar.gz" \
    || die "couldn't download Node.js (check your internet connection)"
  tar -xzf node.tar.gz
  rm -f node.tar.gz
fi
export PATH="$NODE_DIR/bin:$PATH"
ok "Node.js ready ($(node -v))."

# 2. Download the Worker source
say "Downloading the Cobblify stats backend…"
curl -fL# -o source.tar.gz "$REPO_TARBALL" || die "couldn't download the mod source"
# Keep the previous run's npm packages so re-runs don't re-download them.
PREV_NM="$(find "$WORKDIR/src-extract" -type d -path '*/server/stats-worker/node_modules' 2>/dev/null | head -1 || true)"
if [[ -n "$PREV_NM" ]]; then
  rm -rf "$WORKDIR/nm-cache"
  mv "$PREV_NM" "$WORKDIR/nm-cache"
fi
rm -rf src-extract && mkdir src-extract
tar -xzf source.tar.gz -C src-extract
rm -f source.tar.gz
WORKER_DIR="$(find "$WORKDIR/src-extract" -type d -path '*/server/stats-worker' | head -1)"
[[ -n "$WORKER_DIR" ]] || die "couldn't find the worker folder in the download"
cd "$WORKER_DIR"
if [[ -d "$WORKDIR/nm-cache" ]]; then
  mv "$WORKDIR/nm-cache" node_modules
fi
ok "Source ready."

# 3. Install dependencies
say "Installing (this can take a minute)…"
npm install --no-fund --no-audit || die "npm install failed"
ok "Installed."

# 4. Log in to Cloudflare (opens a browser)
npx wrangler login
LOGIN_STATUS="$(npx wrangler whoami 2>&1 || true)"
if printf '%s' "$LOGIN_STATUS" | grep -qiE "not authenticated|not logged|wrangler login"; then
  say "A browser window will open — log in or sign up for Cloudflare, then click \"Allow\"."
  npx wrangler login || die "Cloudflare login was cancelled"
fi
ok "Logged in to Cloudflare."

# 5. Create the provider-key store (KV namespace) and wire it into this copy of wrangler.toml
say "Setting up the provider-key store…"
KV_TITLE="STATS_KV"
KV_OUT="$(npx wrangler kv namespace create STATS_KV 2>&1 || true)"
KV_ID="$(printf '%s\n' "$KV_OUT" | grep -Eo '[0-9a-f]{32}' | head -1 || true)"
if [[ -z "$KV_ID" ]]; then
  # Already exists from an earlier run — look its id up instead.
  KV_ID="$(npx wrangler kv namespace list 2>/dev/null | node -e '
    let d = "";
    process.stdin.on("data", c => d += c).on("end", () => {
      try {
        // Trim to the outermost [...] — wrangler prints banners/update notices around the JSON.
        const i = d.indexOf("["), j = d.lastIndexOf("]");
        if (i < 0 || j <= i) return;
        const ns = JSON.parse(d.slice(i, j + 1)).find(n => n.title === process.argv[1]);
        if (ns && ns.id) process.stdout.write(ns.id);
      } catch (_) {}
    });' "$KV_TITLE" || true)"
fi
[[ -n "$KV_ID" ]] || { printf '%s\n' "$KV_OUT"; die "couldn't create the provider-key store"; }
printf '\n[[kv_namespaces]]\nbinding = "STATS_KV"\nid = "%s"\n' "$KV_ID" >> wrangler.toml
ok "Provider-key store ready."

# 6. Deploy — interactive on purpose: brand-new Cloudflare accounts are asked to register a
# free workers.dev name here, and that prompt only appears when the output is a real terminal.
say "Deploying your stats backend…"
say "(If asked to register a workers.dev subdomain, type anything — e.g. your Minecraft name.)"
npx wrangler deploy || die "deploy failed"

# The URL was printed to the terminal above where we can't read it, so re-deploy captured —
# the subdomain now exists, so this second pass never prompts.
say "Reading your backend address…"
DEPLOY_OUT="$(npx wrangler deploy 2>&1)" || { printf '%s\n' "$DEPLOY_OUT"; die "deploy failed"; }
URL="$(printf '%s\n' "$DEPLOY_OUT" | grep -Eo 'https://[a-zA-Z0-9._-]+\.workers\.dev' | head -1)"
[[ -n "$URL" ]] || { printf '%s\n' "$DEPLOY_OUT"; die "deployed, but couldn't read the URL from the output above"; }
ok "Backend live at $URL"

# 7. Lock the backend with a private token so only this user's mod can use it
say "Locking your backend with a private token…"
TOKEN="$(openssl rand -hex 16 2>/dev/null || head -c 16 /dev/urandom | xxd -p | tr -d '\n')"
[[ -n "$TOKEN" ]] || die "couldn't generate a token"
SECRET_OUT="$(printf '%s' "$TOKEN" | npx wrangler secret put STATS_TOKEN 2>&1)" \
  || { printf '%s\n' "$SECRET_OUT"; die "couldn't set the backend token"; }
ok "Backend locked."

CMD_URL="/cobblify statsurl $URL"
CMD_TOKEN="/cobblify statstoken $TOKEN"
printf '%s' "$CMD_URL" | pbcopy 2>/dev/null || true
# Also save the commands to a file so closing this window loses nothing.
CMD_FILE="$HOME/Desktop/cobblify-commands.txt"
printf '%s\n%s\n' "$CMD_URL" "$CMD_TOKEN" > "$CMD_FILE" 2>/dev/null || CMD_FILE=""

# Done
printf '\n\033[1;32m======================================================\n'
printf   '  DONE!  Your stats backend is live.\n'
printf   '======================================================\033[0m\n\n'
printf 'In Minecraft, paste BOTH commands into chat (the first is on your clipboard):\n\n'
printf '   \033[1;33m%s\033[0m\n' "$CMD_URL"
printf '   \033[1;33m%s\033[0m\n\n' "$CMD_TOKEN"
printf 'Then turn on Hypixel Stats in the mod settings (press Right Shift).\n\n'
printf 'Optional — community cheater tags from Urchin: get a free API key from the\n'
printf 'Urchin Discord bot (/grant), then run in chat:  /cobblify urchinkey <your key>\n\n'
[[ -n "$CMD_FILE" ]] && printf 'Both commands are also saved to:  %s\n\n' "$CMD_FILE"
printf 'You can close this window.\n\n'
