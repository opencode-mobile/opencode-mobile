#!/usr/bin/env bash
# Captures native iOS tiles for the HTML design system gallery.
#
#   scripts/component-gallery/capture-ios.sh [component-id ...]
#
# Builds the app, erases and boots a dedicated "Component Capture iPhone 17" simulator, runs
# ComponentCaptureUITests for every component in captures/contract.json (or just the ids
# given), and writes PNGs plus manifest.json to captures/ios/. The simulator is shut down at
# the end. Env overrides: CAPTURE_OUT (output folder), CAPTURE_CONTRACT,
# CAPTURE_DERIVED_DATA, CAPTURE_SIM_NAME, CAPTURE_DEVICE_TYPE.
#
# Every run erases the simulator, so CAPTURE_SIM_NAME must contain "Component Capture".
# CAPTURE_ALLOW_ERASE=1 overrides that check for another simulator you are sure can be wiped.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GALLERY="$ROOT/scripts/component-gallery"
CONTRACT="${CAPTURE_CONTRACT:-$GALLERY/captures/contract.json}"
OUT="${CAPTURE_OUT:-$GALLERY/captures/ios}"
IOS_DIR="$ROOT/ios/OpenCode Mobile"
SIM_NAME="${CAPTURE_SIM_NAME:-Component Capture iPhone 17}"
DEVICE_TYPE="${CAPTURE_DEVICE_TYPE:-com.apple.CoreSimulator.SimDeviceType.iPhone-17}"
DEVICE_LABEL="iPhone 17"
ONLY="$(IFS=,; echo "${*:-}")"

[[ -f "$CONTRACT" ]] || { echo "missing $CONTRACT" >&2; exit 1; }
if [[ "$SIM_NAME" != *"Component Capture"* && "${CAPTURE_ALLOW_ERASE:-0}" != 1 ]]; then
  echo "refusing to erase simulator \"$SIM_NAME\": its name must contain \"Component Capture\" (or set CAPTURE_ALLOW_ERASE=1)" >&2
  exit 1
fi

RUNTIME="$(xcrun simctl list runtimes -j | /usr/bin/python3 -c '
import json, sys
rs = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["isAvailable"]]
rs.sort(key=lambda r: [int(x) for x in r["version"].split(".")])
print(rs[-1]["identifier"] + " " + rs[-1]["version"]) if rs else sys.exit("no iOS runtime")
')"
RUNTIME_ID="${RUNTIME%% *}"
OS_VERSION="${RUNTIME##* }"

UDID="$(xcrun simctl list devices -j | /usr/bin/python3 -c '
import json, sys
name, runtime = sys.argv[1], sys.argv[2]
for d in json.load(sys.stdin)["devices"].get(runtime, []):
    if d["name"] == name and d["isAvailable"]:
        print(d["udid"]); break
' "$SIM_NAME" "$RUNTIME_ID")"
if [[ -z "$UDID" ]]; then
  echo "creating simulator \"$SIM_NAME\" ($OS_VERSION)"
  UDID="$(xcrun simctl create "$SIM_NAME" "$DEVICE_TYPE" "$RUNTIME_ID")"
fi

STAGE=""
cleanup() {
  xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
  [[ -n "$STAGE" ]] && rm -rf "$STAGE"
}
trap cleanup EXIT

# A clean device every run: no saved theme, text size, or appearance from earlier launches.
xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
xcrun simctl erase "$UDID"
xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl ui "$UDID" content_size large >/dev/null 2>&1 || true
# Components freeze spinners, pulses, and shimmer under Reduce Motion.
xcrun simctl spawn "$UDID" defaults write com.apple.Accessibility ReduceMotionEnabled -bool true

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/component-capture-ios.XXXXXX")"
# One log per run, kept after the staging folder is removed.
LOG="$(mktemp "${TMPDIR:-/tmp}/opencode-component-capture-ios.XXXXXX")"
# Kept between runs so a rerun only rebuilds what changed.
DERIVED_DATA="${CAPTURE_DERIVED_DATA:-${TMPDIR:-/tmp}/opencode-component-capture-ios-dd}"
echo "capturing on $SIM_NAME ($UDID), log: $LOG"

START=$(date +%s)
set +e
(
  cd "$IOS_DIR"
  TEST_RUNNER_COMPONENT_CAPTURE_OUT="$STAGE/out" \
  TEST_RUNNER_COMPONENT_CAPTURE_CONTRACT="$CONTRACT" \
  TEST_RUNNER_COMPONENT_CAPTURE_ONLY="$ONLY" \
  xcodebuild test \
    -project "OpenCode Mobile.xcodeproj" \
    -scheme "OpenCode Mobile" \
    -destination "platform=iOS Simulator,id=$UDID" \
    -derivedDataPath "$DERIVED_DATA" \
    -parallel-testing-enabled NO \
    -only-testing:"OpenCode MobileUITests/ComponentCaptureUITests" \
    TEST_RUNNER_COMPONENT_CAPTURE_OUT="$STAGE/out" \
    TEST_RUNNER_COMPONENT_CAPTURE_CONTRACT="$CONTRACT" \
    TEST_RUNNER_COMPONENT_CAPTURE_ONLY="$ONLY"
) >"$LOG" 2>&1
STATUS=$?
set -e
if [[ $STATUS -ne 0 ]]; then
  grep -E "error:|failed|XCTAssert|XCTFail" "$LOG" | tail -30 >&2 || true
  echo "xcodebuild test failed ($STATUS); full log: $LOG" >&2
  exit "$STATUS"
fi

mkdir -p "$OUT"
GIT_SHA="$(git -C "$ROOT" rev-parse HEAD)"
/usr/bin/python3 - "$STAGE/out" "$OUT" "$DEVICE_LABEL" "iOS $OS_VERSION" "$GIT_SHA" "$ONLY" <<'PY'
import datetime, json, os, shutil, sys

stage, out, device, os_label, sha, only = sys.argv[1:7]
records_path = os.path.join(stage, "records.json")
if not os.path.exists(records_path):
    sys.exit("no capture records written; did the UI test run?")
records = json.load(open(records_path))
covered = set(records["components"])

# A partial run (component ids given) replaces only those components in the manifest.
manifest_path = os.path.join(out, "manifest.json")
previous = json.load(open(manifest_path)) if only and os.path.exists(manifest_path) else {}
captures = [c for c in previous.get("captures", []) if c["component"] not in covered]
skipped = [s for s in previous.get("skipped", []) if s["component"] not in covered]
extras = [e for e in previous.get("extraStates", []) if e.split("/")[0] not in covered]

for component in sorted(covered):
    dest = os.path.join(out, component)
    shutil.rmtree(dest, ignore_errors=True)
    if os.path.isdir(os.path.join(stage, component)):
        shutil.copytree(os.path.join(stage, component), dest)

for c in records["captures"]:
    entry = {k: c[k] for k in ("component", "state", "appearance", "file")}
    entry |= {"widthPt": round(c["widthPt"], 2), "heightPt": round(c["heightPt"], 2)}
    if c.get("note"):
        entry["note"] = c["note"]
    captures.append(entry)
skipped += records["skipped"]
extras += records["extraStates"]

manifest = {
    "platform": "ios",
    "device": device,
    "os": os_label,
    "scale": 3,
    "theme": "porcelain",
    "gitSha": sha,
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
    "captures": captures,
    "skipped": skipped,
    "extraStates": sorted(set(extras)),
}
with open(manifest_path, "w") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")
new_tiles = len(records["captures"])
print(f"this run: {new_tiles} tiles, {len(records['skipped'])} skipped; "
      f"manifest: {len(captures)} tiles, {len(skipped)} skipped -> {out}")
PY

echo "capture took $(( $(date +%s) - START ))s"
rm -rf "$STAGE"
