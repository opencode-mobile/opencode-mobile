#!/bin/sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$ROOT_DIR/ios/OpenCode Mobile/OpenCode Mobile.xcodeproj"
SCHEME="${IOS_SCHEME:-OpenCode Mobile}"
BUNDLE_ID="${IOS_BUNDLE_ID:-dev.opencode.mobile}"
DERIVED_DATA_PATH="${IOS_DERIVED_DATA_PATH:-$ROOT_DIR/build/ios}"

if [ ! -d "$PROJECT" ]; then
  echo "Missing Xcode project at $PROJECT" >&2
  exit 1
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "xcodebuild was not found. Install Xcode command line tools." >&2
  exit 1
fi

if ! command -v xcrun >/dev/null 2>&1; then
  echo "xcrun was not found. Install Xcode command line tools." >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 was not found. It is required to choose an iOS simulator." >&2
  exit 1
fi

pick_simulator() {
  IOS_SIMULATOR_NAME="${IOS_SIMULATOR_NAME:-}" xcrun simctl list devices available -j | python3 -c '
import json
import os
import sys

target = os.environ.get("IOS_SIMULATOR_NAME")
data = json.load(sys.stdin)
candidates = []

for runtime, devices in data.get("devices", {}).items():
    if "iOS" not in runtime:
        continue
    for device in devices:
        if not device.get("isAvailable") or "iPhone" not in device.get("name", ""):
            continue
        candidates.append(device)

if target:
    for device in candidates:
        if device.get("name") == target:
            print("{}|{}".format(device["udid"], device["name"]))
            sys.exit(0)
    print(f"No available iPhone simulator named {target!r}.", file=sys.stderr)
    sys.exit(2)

booted = [device for device in candidates if device.get("state") == "Booted"]
chosen = booted[0] if booted else candidates[0] if candidates else None
if not chosen:
    print("No available iPhone simulators found.", file=sys.stderr)
    sys.exit(2)

print("{}|{}".format(chosen["udid"], chosen["name"]))
'
}

SIMULATOR_INFO="$(pick_simulator)"
SIMULATOR_UDID="${SIMULATOR_INFO%%|*}"
SIMULATOR_NAME="${SIMULATOR_INFO#*|}"

if [ -z "${SIMULATOR_UDID:-}" ]; then
  echo "Could not choose an iOS simulator." >&2
  exit 1
fi

if ! BOOT_OUTPUT="$(xcrun simctl boot "$SIMULATOR_UDID" 2>&1)"; then
  case "$BOOT_OUTPUT" in
    *"current state: Booted"*) ;;
    *)
      echo "$BOOT_OUTPUT" >&2
      exit 1
      ;;
  esac
fi

open -a Simulator
xcrun simctl bootstatus "$SIMULATOR_UDID" -b

xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  build

APP_PATH="$DERIVED_DATA_PATH/Build/Products/Debug-iphonesimulator/$SCHEME.app"

if [ ! -d "$APP_PATH" ]; then
  echo "Built app not found at $APP_PATH" >&2
  exit 1
fi

xcrun simctl install "$SIMULATOR_UDID" "$APP_PATH"
xcrun simctl launch "$SIMULATOR_UDID" "$BUNDLE_ID"

echo "Launched $SCHEME on $SIMULATOR_NAME."
