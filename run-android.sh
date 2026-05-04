#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
APP_ID="${ANDROID_APP_ID:-dev.opencode.mobile}"
ADB="${ADB:-adb}"

if [[ ! -x "$ANDROID_DIR/gradlew" ]]; then
  echo "Missing Android Gradle wrapper at $ANDROID_DIR/gradlew" >&2
  exit 1
fi

if ! command -v "$ADB" >/dev/null 2>&1; then
  if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
    ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
  else
    echo "adb was not found. Install Android platform-tools or set ADB." >&2
    exit 1
  fi
fi

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  DEVICE="$ANDROID_SERIAL"
else
  DEVICE="$("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }')"
fi

if [[ -z "${DEVICE:-}" ]]; then
  echo "No running Android emulator found." >&2
  echo "Start an emulator first, or set ANDROID_SERIAL to a target device." >&2
  exit 1
fi

"$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" :app:installDebug
"$ADB" -s "$DEVICE" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1
