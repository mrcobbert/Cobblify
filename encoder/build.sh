#!/usr/bin/env bash
# Builds the release helper for this machine's OS into encoder/dist/.
#   macOS:   universal (arm64 + x86_64) encoder/dist/cobblify-encode, ad-hoc signed
#   Windows: encoder/dist/cobblify-encode.exe (x64; run from Git Bash)
# Honours CARGO_TARGET_DIR (the local release runners point it at their own cache).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
target_dir="${CARGO_TARGET_DIR:-$here/target}"
case "$target_dir" in
  /* | [A-Za-z]:[\\/]*) ;;
  *) target_dir="$PWD/$target_dir" ;;
esac
export CARGO_TARGET_DIR="$target_dir"
manifest="$here/Cargo.toml"
dist="$here/dist"
mkdir -p "$dist"

case "$(uname -s)" in
  Darwin)
    # Same floor as the launcher (LSMinimumSystemVersion 11.0).
    export MACOSX_DEPLOYMENT_TARGET=11.0
    for target in aarch64-apple-darwin x86_64-apple-darwin; do
      cargo build --release --locked --manifest-path "$manifest" --target "$target"
    done
    rm -f "$dist/cobblify-encode"
    lipo -create -output "$dist/cobblify-encode" \
      "$target_dir/aarch64-apple-darwin/release/cobblify-encode" \
      "$target_dir/x86_64-apple-darwin/release/cobblify-encode"
    codesign --force -s - "$dist/cobblify-encode"
    out="$dist/cobblify-encode"
    ;;
  MINGW* | MSYS* | CYGWIN*)
    cargo build --release --locked --manifest-path "$manifest"
    cp -f "$target_dir/release/cobblify-encode.exe" "$dist/cobblify-encode.exe"
    out="$dist/cobblify-encode.exe"
    ;;
  *)
    echo "build.sh: unsupported OS $(uname -s)" >&2
    exit 1
    ;;
esac

echo "built $out ($(wc -c <"$out" | tr -d ' ') bytes)"
