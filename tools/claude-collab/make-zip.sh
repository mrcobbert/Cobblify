#!/usr/bin/env bash
# Package this folder for sharing (AirDrop / zip send).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
OUT_DIR="$REPO/dist-owner"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/claude-collab-skills.zip"

rm -f "$OUT"
(
  cd "$ROOT/.."
  zip -r "$OUT" claude-collab \
    -x "claude-collab/.DS_Store" \
    -x "claude-collab/**/.DS_Store"
)

echo "wrote $OUT"
echo "friend: unzip, then cd claude-collab && chmod +x install.sh && ./install.sh"
