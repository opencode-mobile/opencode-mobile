#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

link_path() {
  local link_path="$1"
  local target="$2"
  local parent

  parent="$(dirname "$link_path")"
  if [[ "$parent" != "." ]]; then
    mkdir -p "$parent"
  fi

  if [[ -L "$link_path" ]]; then
    local current
    current="$(readlink "$link_path")"
    if [[ "$current" == "$target" ]]; then
      echo "ok: $link_path -> $target"
      return 0
    fi
    rm "$link_path"
  elif [[ -e "$link_path" ]]; then
    echo "error: $link_path exists and is not a symlink" >&2
    exit 1
  fi

  ln -s "$target" "$link_path"
  echo "linked: $link_path -> $target"
}

if [[ ! -d ".agents" ]]; then
  echo "error: .agents directory is missing" >&2
  exit 1
fi

if [[ ! -f "AGENTS.md" ]]; then
  echo "error: AGENTS.md is missing" >&2
  exit 1
fi

link_path ".cursor/skills" "../.agents"
link_path ".claude/skills" "../.agents"
link_path "CLAUDE.md" "AGENTS.md"

echo "Agent symlinks are up to date."
