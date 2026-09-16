#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="${ROOT}/VERSION"

if [[ ! -f "$VERSION_FILE" ]]; then
  echo "VERSION file is missing at ${VERSION_FILE}." >&2
  exit 1
fi

VERSION="$(tr -d '[:space:]' < "$VERSION_FILE")"
# App Store marketing versions must be 1–3 period-separated integers.
# Keep the repo file on that shape so iOS, Android, and CI share one value.
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "VERSION must be MAJOR.MINOR.PATCH with integers only (got: ${VERSION})." >&2
  exit 1
fi

printf '%s\n' "$VERSION"
