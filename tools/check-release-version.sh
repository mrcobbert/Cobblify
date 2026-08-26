#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

read_toml_version() { sed -n 's/^version = "\([^"]*\)"$/\1/p' "$1" | head -1; }
read_json_version() {
  node -e 'const fs=require("fs"); console.log(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).version)' "$1"
}

expected=$(sed -n 's/^mod_version=//p' gradle.properties | head -1)
[ -n "$expected" ] || { echo "gradle.properties has no mod_version" >&2; exit 1; }

check() {
  local label=$1 actual=$2
  if [ "$actual" != "$expected" ]; then
    echo "version mismatch: $label is '$actual', expected '$expected'" >&2
    exit 1
  fi
}

check "lunar/build.gradle.kts" "$(sed -n 's/^version = "\([^"]*\)"$/\1/p' lunar/build.gradle.kts | head -1)"
check "launcher/package.json" "$(read_json_version launcher/package.json)"
check "launcher/package-lock.json" "$(read_json_version launcher/package-lock.json)"
check "launcher/src-tauri/Cargo.toml" "$(read_toml_version launcher/src-tauri/Cargo.toml)"
check "launcher/src-tauri/tauri.conf.json" "$(read_json_version launcher/src-tauri/tauri.conf.json)"

if [ -n "${GITHUB_REF_NAME:-}" ] && [[ "${GITHUB_REF:-}" == refs/tags/v* ]]; then
  check "tag" "${GITHUB_REF_NAME#v}"
fi

echo "release version aligned: $expected"
