#!/usr/bin/env bash
# Deploy stats Worker and run definitive probe.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v npm >/dev/null; then
  echo "npm required"; exit 1
fi

if [[ ! -d node_modules ]]; then
  npm install
fi

if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]] && ! npx wrangler whoami 2>/dev/null | grep -q "You are logged in"; then
  echo "Not logged in to Cloudflare."
  echo ""
  echo "  Option A (browser):  npx wrangler login"
  echo "  Option B (CI/token): export CLOUDFLARE_API_TOKEN=..."
  echo "                       https://developers.cloudflare.com/fundamentals/api/get-started/create-token/"
  echo "                       Permissions: Account > Workers Scripts > Edit"
  echo ""
  exit 1
fi

# The owner must pass WRANGLER_CONFIG=wrangler.owner.toml. A plain deploy uses
# wrangler.toml, which on the owner account strips the STATS_KV, R2 and D1 bindings and
# silently disables the providers and launcher updates (README, "Two deployment
# configs"). WORKER_TOKEN, if set, passes through the environment to the probe below.
config="${WRANGLER_CONFIG:-wrangler.toml}"

echo "Deploying Worker with $config..."
deploy_out=$(npx wrangler deploy -c "$config" 2>&1)
echo "$deploy_out"

# wrangler deploy prints: https://bedwarsqol-stats.<subdomain>.workers.dev
worker_url=$(echo "$deploy_out" | grep -Eo 'https://[a-zA-Z0-9._-]+\.workers\.dev' | head -1)
if [[ -z "$worker_url" ]]; then
  echo "Could not parse workers.dev URL from deploy output."
  echo "Set WORKER_URL manually and run: npm run test:worker"
  exit 1
fi

export WORKER_URL="$worker_url"
echo ""
echo "Deployed at: $WORKER_URL"
echo "Running definitive Worker egress probe..."
echo ""

WORKER_URL="$WORKER_URL" npm run test:worker
exit $?
