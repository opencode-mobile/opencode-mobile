#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="store-screenshots"
OPEN=1

usage() {
  cat <<EOF
Usage: ./store-screenshots-gallery.sh [--out <dir>] [--no-open]

Rebuild the HTML gallery (index.html) for existing store-screenshot captures
and open it in the default browser.

Options:
  --out <dir>   Screenshot directory (default: $OUT_DIR)
  --no-open     Write the page without opening it
  -h, --help    Show this help
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out)
      [ $# -ge 2 ] || { echo "--out requires a directory" >&2; exit 2; }
      OUT_DIR="$2"
      shift 2
      ;;
    --no-open)
      OPEN=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

command -v node >/dev/null 2>&1 || { echo "node is required (>= 22)" >&2; exit 1; }

cd "$ROOT_DIR"
node scripts/store-screenshots/write-gallery.mjs --out "$OUT_DIR"

PAGE="$OUT_DIR/index.html"
[ -f "$PAGE" ] || { echo "Gallery not written: $PAGE" >&2; exit 1; }
echo "Gallery: $ROOT_DIR/$PAGE"

if [ "$OPEN" -eq 1 ]; then
  if command -v open >/dev/null 2>&1; then
    open "$PAGE"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$PAGE"
  fi
fi
