#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Honor store workflow override. Do not fall back to Xcode MARKETING_VERSION:
# local builds already expose the current Info.plist value there.
if [[ -n "${IOS_MARKETING_VERSION:-}" ]]; then
  VERSION="$IOS_MARKETING_VERSION"
else
  VERSION="$("$ROOT/scripts/read-app-version.sh")"
fi
INFO="${TARGET_BUILD_DIR:?}/${INFOPLIST_PATH:?}"

if [[ ! -f "$INFO" ]]; then
  echo "error: Info.plist not found at ${INFO}." >&2
  exit 1
fi

if /usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$INFO" >/dev/null 2>&1; then
  /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$INFO"
else
  /usr/libexec/PlistBuddy -c "Add :CFBundleShortVersionString string $VERSION" "$INFO"
fi
