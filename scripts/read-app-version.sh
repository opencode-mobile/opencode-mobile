#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="${1:-}"
VERSION_FILE="${ROOT}/VERSION"

# iOS ships on its own version line while ios/VERSION exists (it has not had a
# public App Store release yet). Delete ios/VERSION to rejoin the shared one.
case "$PLATFORM" in
  "") ;;
  ios)
    if [[ -f "${ROOT}/ios/VERSION" ]]; then
      VERSION_FILE="${ROOT}/ios/VERSION"
    fi
    ;;
  *)
    echo "Unknown platform: ${PLATFORM} (expected: ios, or no argument)." >&2
    exit 1
    ;;
esac

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "Version file is missing at ${VERSION_FILE}." >&2
  exit 1
fi

VERSION="$(tr -d '[:space:]' < "$VERSION_FILE")"
# App Store marketing versions must be 1–3 period-separated integers.
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "${VERSION_FILE} must be MAJOR.MINOR.PATCH with integers only (got: ${VERSION})." >&2
  exit 1
fi

printf '%s\n' "$VERSION"
