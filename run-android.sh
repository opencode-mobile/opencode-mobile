#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
APP_ID="${ANDROID_APP_ID:-dev.opencode.mobile}"
ADB="${ADB:-adb}"
VERBOSE=0

usage() {
  cat <<EOF
Usage: ./run-android.sh [-v|--v|--verbose]

Build, install, and launch $APP_ID on a connected device or emulator.

Options:
  -v, --v, --verbose   Print full command output
EOF
}

for arg in "$@"; do
  case "$arg" in
    -v|--v|--verbose)
      VERBOSE=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      usage >&2
      exit 1
      ;;
  esac
done

run_quiet() {
  local label="$1"
  shift
  if [[ "$VERBOSE" -eq 1 ]]; then
    "$@"
    return
  fi

  local log
  log="$(mktemp -t "opencode-run-android.${label}.XXXXXX")"
  local status=0
  "$@" >"$log" 2>&1 || status=$?
  if [[ "$status" -eq 0 ]]; then
    rm -f "$log"
    return 0
  fi
  echo "error: $label failed (exit $status)" >&2
  tail -n 50 "$log" >&2 || true
  echo "Full log: $log (re-run with --v for live output)" >&2
  exit "$status"
}

if [[ ! -x "$ANDROID_DIR/gradlew" ]]; then
  echo "error: Missing Android Gradle wrapper at $ANDROID_DIR/gradlew" >&2
  exit 1
fi

resolve_android_sdk() {
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    echo "$ANDROID_HOME"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    echo "$ANDROID_SDK_ROOT"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    echo "$HOME/Library/Android/sdk"
  fi
}

SDK="$(resolve_android_sdk)"

if ! command -v "$ADB" >/dev/null 2>&1; then
  if [[ -n "$SDK" && -x "$SDK/platform-tools/adb" ]]; then
    ADB="$SDK/platform-tools/adb"
  else
    echo "error: adb was not found. Install Android platform-tools or set ADB." >&2
    exit 1
  fi
fi

EMULATOR="${EMULATOR:-}"
if [[ -z "$EMULATOR" && -n "$SDK" && -x "$SDK/emulator/emulator" ]]; then
  EMULATOR="$SDK/emulator/emulator"
fi

find_physical_device() {
  "$ADB" devices | awk '$1 !~ /^emulator-/ && $2 == "device" { print $1; exit }'
}

find_running_emulator() {
  "$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1; exit }'
}

wait_for_emulator_boot() {
  local device="$1"
  if [[ "$VERBOSE" -eq 1 ]]; then
    echo "Waiting for $device to finish booting..."
  fi
  "$ADB" -s "$device" wait-for-device
  until [[ "$("$ADB" -s "$device" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 1
  done
}

pick_avd() {
  if [[ -z "$EMULATOR" ]]; then
    echo "error: emulator was not found. Install the Android Emulator or set EMULATOR." >&2
    exit 1
  fi

  local avds
  avds="$("$EMULATOR" -list-avds)"
  if [[ -z "$avds" ]]; then
    echo "error: No Android Virtual Devices found. Create one in Android Studio." >&2
    exit 1
  fi

  if [[ -n "${ANDROID_AVD:-}" ]]; then
    if echo "$avds" | grep -Fxq "$ANDROID_AVD"; then
      echo "$ANDROID_AVD"
      return
    fi
    echo "error: No AVD named '${ANDROID_AVD}'." >&2
    exit 1
  fi

  echo "$avds" | head -n 1
}

start_emulator() {
  local avd="$1"
  if [[ "$VERBOSE" -eq 1 ]]; then
    echo "Starting Android emulator: $avd"
  fi
  "$EMULATOR" -avd "$avd" -no-boot-anim >/dev/null 2>&1 &
}

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  DEVICE="$ANDROID_SERIAL"
else
  DEVICE="$(find_physical_device)"
  if [[ -z "${DEVICE:-}" ]]; then
    DEVICE="$(find_running_emulator)"
  fi
fi

if [[ -z "${DEVICE:-}" ]]; then
  AVD="$(pick_avd)"
  start_emulator "$AVD"
  "$ADB" wait-for-device
  DEVICE="$(find_running_emulator)"
  if [[ -z "${DEVICE:-}" ]]; then
    echo "error: Emulator started but no device serial was detected." >&2
    exit 1
  fi
  wait_for_emulator_boot "$DEVICE"
fi

run_quiet install "$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" :app:installDebug
run_quiet launch "$ADB" -s "$DEVICE" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1

echo "success: Launched $APP_ID on $DEVICE."
