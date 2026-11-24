#!/usr/bin/env bash
# Captures native Android renders of the component gallery states listed in
# captures/contract.json, in Porcelain light and dark, on an emulator.
#
#   scripts/component-gallery/capture-android.sh [component-id ...]
#
# With no ids it captures every component in the Android capture registry
# (android/app/src/debug/.../ui/componentgallery/capture/ComponentCaptureRegistry.kt).
#
# It builds the debug app and test APKs, boots a dedicated AVD (created from the
# newest installed system image if missing), runs ComponentCaptureTest with
# `am instrument` on that one device, and writes PNGs plus manifest.json to
# scripts/component-gallery/captures/android/. It shuts down an emulator it booted.
#
# Environment:
#   CAPTURE_OUT           output folder (default: captures/android next to this script)
#   CAPTURE_CONTRACT      contract.json (default: next to the output folder)
#   CAPTURE_AVD           AVD name (default: component_capture_phone)
#   CAPTURE_DEVICE        hardware profile for a new AVD (default: pixel_9)
#   CAPTURE_SYSTEM_IMAGE  system image package for a new AVD (default: newest installed)
#   CAPTURE_SKIP_BUILD=1  reuse the APKs already built
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
OUT="${CAPTURE_OUT:-$SCRIPT_DIR/captures/android}"
CONTRACT="${CAPTURE_CONTRACT:-$OUT/../contract.json}"
AVD="${CAPTURE_AVD:-component_capture_phone}"
HW_DEVICE="${CAPTURE_DEVICE:-pixel_9}"
APP_ID="dev.opencode.mobile"
TEST_CLASS="dev.opencode.mobile.ui.componentgallery.capture.ComponentCaptureTest"
DEVICE_OUT="/sdcard/Android/data/$APP_ID/files/component-captures"
COMPONENTS="$(IFS=,; echo "$*")"

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

# ---- SDK ----
sdk_from_properties() {
  local props="$ANDROID_DIR/local.properties"
  [[ -f "$props" ]] && sed -n 's/^sdk\.dir=//p' "$props" | head -1
}
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sdk_from_properties || true)}}"
SDK="${SDK:-$HOME/Library/Android/sdk}"
[[ -d "$SDK" ]] || die "Android SDK not found; set ANDROID_HOME"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
for tool in "$ADB" "$EMULATOR"; do [[ -x "$tool" ]] || die "missing $tool"; done
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"

# ---- Build ----
if [[ "${CAPTURE_SKIP_BUILD:-0}" != 1 ]]; then
  log "Building debug app and test APKs"
  (cd "$ANDROID_DIR" && ./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest)
fi
APP_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ANDROID_DIR/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APP_APK" && -f "$TEST_APK" ]] || die "APKs missing; run without CAPTURE_SKIP_BUILD"

# ---- Emulator ----
serial_for_avd() {
  local serial
  for serial in $("$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1}'); do
    if [[ "$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')" == "$AVD" ]]; then
      echo "$serial"
      return
    fi
  done
}

if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
  [[ -x "$AVDMANAGER" ]] || die "avdmanager not found; install the Android SDK command-line tools"
  IMAGE="${CAPTURE_SYSTEM_IMAGE:-}"
  if [[ -z "$IMAGE" ]]; then
    IMAGE_DIR="$(ls -d "$SDK"/system-images/*/*/* 2>/dev/null | sort -V | tail -1)"
    [[ -n "$IMAGE_DIR" ]] || die "no system image installed; set CAPTURE_SYSTEM_IMAGE"
    IMAGE="system-images;$(echo "${IMAGE_DIR#"$SDK"/system-images/}" | tr / ';')"
  fi
  log "Creating AVD $AVD ($HW_DEVICE, $IMAGE)"
  echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d "$HW_DEVICE" >/dev/null
fi

SERIAL="$(serial_for_avd)"
BOOTED=0
if [[ -z "$SERIAL" ]]; then
  log "Booting $AVD"
  "$EMULATOR" -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot >/dev/null 2>&1 &
  BOOTED=1
  for _ in $(seq 1 90); do
    sleep 2
    SERIAL="$(serial_for_avd)"
    [[ -n "$SERIAL" ]] && break
  done
  [[ -n "$SERIAL" ]] || die "emulator $AVD did not come up"
fi
cleanup() {
  if [[ "$BOOTED" == 1 && -n "${SERIAL:-}" ]]; then
    log "Shutting down $SERIAL"
    "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

adb_s() { "$ADB" -s "$SERIAL" "$@"; }
adb_s wait-for-device
for _ in $(seq 1 120); do
  [[ "$(adb_s shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
  sleep 2
done
[[ "$(adb_s shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || die "$SERIAL did not finish booting"
log "Using $SERIAL"

# Deterministic frames: no system animations, no soft keyboard, no font or display scaling.
adb_s shell settings put global window_animation_scale 0
adb_s shell settings put global transition_animation_scale 0
adb_s shell settings put global animator_duration_scale 0
adb_s shell settings put secure show_ime_with_hard_keyboard 0
# Overlay tiles hide the system bars; skip the one-time "Viewing full screen" hint.
adb_s shell settings put secure immersive_mode_confirmations confirmed
adb_s shell settings put system font_scale 1.0
adb_s shell wm density reset
adb_s shell wm size reset

# ---- Capture ----
log "Installing APKs"
adb_s install -r -t "$APP_APK" >/dev/null
adb_s install -r -t "$TEST_APK" >/dev/null
RUNNER="$(adb_s shell pm list instrumentation | tr -d '\r' | sed -n "s/^instrumentation:\(${APP_ID//./\\.}\.test\/[^ ]*\) (target=${APP_ID//./\\.})$/\1/p" | head -1)"
[[ -n "$RUNNER" ]] || die "test instrumentation for $APP_ID not found"

adb_s shell rm -rf "$DEVICE_OUT"
log "Capturing${COMPONENTS:+ $COMPONENTS}"
ARGS=(-e class "$TEST_CLASS")
[[ -n "$COMPONENTS" ]] && ARGS+=(-e captureComponents "$COMPONENTS")
RESULT="$(adb_s shell am instrument -w -r "${ARGS[@]}" "$RUNNER" | tr -d '\r')"
if ! grep -q '^OK (1 test)' <<<"$RESULT"; then
  echo "$RESULT" | tail -40 >&2
  die "ComponentCaptureTest failed"
fi

PULL="$(mktemp -d)"
trap 'rm -rf "$PULL"; cleanup' EXIT
adb_s pull "$DEVICE_OUT" "$PULL" >/dev/null
SRC="$PULL/component-captures"
[[ -f "$SRC/captures.json" ]] || die "no captures.json pulled from $DEVICE_OUT"

# ---- Output ----
mkdir -p "$OUT"
HW_NAME="$(sed -n 's/^hw\.device\.name *= *//p' "$HOME/.android/avd/$AVD.avd/config.ini" 2>/dev/null | head -1)"
DEVICE_LABEL="${HW_NAME:-$HW_DEVICE} emulator ($AVD)"
[[ -f "$CONTRACT" ]] || die "contract not found at $CONTRACT; set CAPTURE_CONTRACT"
node - "$SRC" "$OUT" "$(git -C "$REPO_ROOT" rev-parse HEAD)" "$DEVICE_LABEL" "$CONTRACT" <<'NODE'
const fs = require("fs");
const path = require("path");
const [src, out, gitSha, device, contractPath] = process.argv.slice(2);
const contract = JSON.parse(fs.readFileSync(contractPath, "utf8"));
const run = JSON.parse(fs.readFileSync(path.join(src, "captures.json"), "utf8"));
const touched = new Set([...run.captures, ...run.skipped].map((c) => c.component));
const manifestPath = path.join(out, "manifest.json");
const previous = fs.existsSync(manifestPath) ? JSON.parse(fs.readFileSync(manifestPath, "utf8")) : { captures: [], skipped: [] };
// A partial run replaces only the components it captured.
for (const id of touched) fs.rmSync(path.join(out, id), { recursive: true, force: true });
for (const c of run.captures) {
  fs.mkdirSync(path.join(out, path.dirname(c.file)), { recursive: true });
  fs.copyFileSync(path.join(src, c.file), path.join(out, c.file));
}
const keep = (list) => (list ?? []).filter((c) => !touched.has(c.component));
const manifest = {
  platform: run.platform,
  device,
  os: run.os,
  scale: run.scale,
  theme: run.theme,
  gitSha,
  generatedAt: new Date().toISOString(),
  captures: [...keep(previous.captures), ...run.captures],
  skipped: [...keep(previous.skipped), ...run.skipped],
};
// Components that exist only on the other platform are skipped once, from the contract.
for (const c of contract.components) {
  if (c.platforms.includes("android")) continue;
  manifest.skipped = manifest.skipped.filter((s) => s.component !== c.id);
  manifest.skipped.push({ component: c.id, reason: `Android has no ${c.title}; ${c.platforms.join(", ")} only` });
}
fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + "\n");

// Coverage: every contract state on Android is captured in both appearances or skipped.
const has = new Set(manifest.captures.map((c) => `${c.component}/${c.state}/${c.appearance}`));
const skipped = new Set(manifest.skipped.map((s) => (s.state ? `${s.component}/${s.state}` : s.component)));
const missing = [];
const byCategory = {};
for (const c of contract.components) {
  const row = (byCategory[c.category] ??= { components: 0, captured: 0, skipped: 0, missing: 0 });
  row.components += 1;
  const extra = manifest.captures.filter((x) => x.component === c.id && !c.states.some((s) => s.id === x.state));
  row.captured += extra.length;
  for (const s of c.states) {
    if (skipped.has(c.id) || skipped.has(`${c.id}/${s.id}`)) { row.skipped += 2; continue; }
    for (const a of contract.appearances) {
      if (has.has(`${c.id}/${s.id}/${a}`)) row.captured += 1;
      else { row.missing += 1; missing.push(`${c.id}/${s.id}--${a}`); }
    }
  }
}
console.log(`This run: ${run.captures.length} tiles, ${run.skipped.length} state skips -> ${out}`);
console.log("Manifest by category (tiles; skips counted per state and appearance):");
for (const [cat, r] of Object.entries(byCategory)) console.log(`  ${cat.padEnd(12)} ${String(r.components).padStart(3)} components  ${String(r.captured).padStart(4)} captured  ${String(r.skipped).padStart(3)} skipped  ${String(r.missing).padStart(4)} missing`);
if (missing.length) console.log(`Missing ${missing.length} tiles, first: ${missing.slice(0, 8).join(", ")}`);
NODE
