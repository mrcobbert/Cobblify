#!/usr/bin/env bash
# Install shared Claude Code skills: /deep-change, /grill-me, /grilling
# Usage:
#   ./install.sh                 # personal skills only (~/.claude/skills)
#   ./install.sh --with-project  # also seed .ai/workflows in current repo
#   ./install.sh --project /path/to/repo
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
DEST="${HOME}/.claude/skills"
WITH_PROJECT=0
PROJECT_DIR=""

usage() {
  cat <<'EOF'
Install Claude Code collaboration skills (deep-change + grill-me).

  ./install.sh [--with-project] [--project <repo>]

  --with-project   Copy project-seed/workflows into <repo>/.ai/workflows/
                   if those files are missing (never overwrites).
  --project DIR    Repo to seed (default: current directory).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --with-project) WITH_PROJECT=1; shift ;;
    --project)
      PROJECT_DIR="${2:-}"
      if [[ -z "$PROJECT_DIR" ]]; then
        echo "error: --project needs a path" >&2
        exit 1
      fi
      shift 2
      ;;
    *)
      echo "error: unknown arg: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$PROJECT_DIR" ]]; then
  PROJECT_DIR="$(pwd)"
fi

mkdir -p "$DEST"

install_skill() {
  local name="$1"
  local src="$ROOT/skills/$name"
  local dst="$DEST/$name"
  if [[ ! -d "$src" ]]; then
    echo "error: missing skill package: $src" >&2
    exit 1
  fi
  mkdir -p "$dst"
  # Replace skill contents so updates stick; do not touch unrelated skills.
  rm -rf "$dst"
  mkdir -p "$(dirname "$dst")"
  cp -R "$src" "$dst"
  echo "installed: $dst"
}

install_skill deep-change
install_skill grill-me
install_skill grilling

if [[ "$WITH_PROJECT" -eq 1 ]]; then
  if [[ ! -d "$PROJECT_DIR" ]]; then
    echo "error: project dir not found: $PROJECT_DIR" >&2
    exit 1
  fi
  seed_src="$ROOT/project-seed/workflows"
  seed_dst="$PROJECT_DIR/.ai/workflows"
  mkdir -p "$seed_dst"
  shopt -s nullglob
  for f in "$seed_src"/*; do
    base="$(basename "$f")"
    target="$seed_dst/$base"
    if [[ -e "$target" ]]; then
      echo "keep existing: $target"
    else
      cp "$f" "$target"
      echo "seeded: $target"
    fi
  done
  shopt -u nullglob
  mkdir -p "$PROJECT_DIR/.ai"
  for stub in TASK.md PLAN.md REVIEW.md HANDOFF.md; do
    if [[ ! -e "$PROJECT_DIR/.ai/$stub" ]]; then
      case "$stub" in
        TASK.md)
          cat >"$PROJECT_DIR/.ai/$stub" <<'EOF'
# Task

## Problem
<!-- observed vs expected -->

## Scope
<!-- in / out -->

## Constraints
<!-- hard limits -->

## Acceptance criteria
<!-- falsifiable done conditions -->

## Stage
DRAFT
EOF
          ;;
        *)
          printf '# %s\n\n(empty)\n' "${stub%.md}" >"$PROJECT_DIR/.ai/$stub"
          ;;
      esac
      echo "seeded: $PROJECT_DIR/.ai/$stub"
    else
      echo "keep existing: $PROJECT_DIR/.ai/$stub"
    fi
  done
fi

cat <<EOF

Done.

Slash commands (restart Claude Code if they do not appear):
  /deep-change
  /grill-me
  /grilling

Prereqs for full /deep-change parity:
  - Claude Code CLI
  - Codex CLI on PATH (used for independent plan/code review handoff)
  - In a project: AGENTS.md / CLAUDE.md, plus .ai/workflows/deep-change.md
    (use --with-project to seed the workflow if missing)

Check:
  ls "$DEST"
  which claude
  which codex || echo "(codex missing: deep-change will ask how to proceed at review stages)"
EOF
