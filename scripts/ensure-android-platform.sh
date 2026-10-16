#!/usr/bin/env bash
set -euo pipefail

# Installs platforms/android-<api> when the runner SDK does not have it yet.
# compileSdk can move ahead of the image baked into a self-hosted runner.
API_LEVEL="${1:?usage: ensure-android-platform.sh <api-level>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" && -f "${ROOT}/android/local.properties" ]]; then
  sdk="$(sed -n 's/^sdk.dir=//p' "${ROOT}/android/local.properties" | head -1)"
fi
if [[ -z "$sdk" || ! -d "$sdk" ]]; then
  echo "Android SDK not found. Set ANDROID_HOME or android/local.properties sdk.dir." >&2
  exit 1
fi

platform_dir="${sdk}/platforms/android-${API_LEVEL}"
if [[ -d "$platform_dir" ]]; then
  echo "platforms/android-${API_LEVEL} already installed at ${sdk}"
  exit 0
fi

manager="$(find "${sdk}/cmdline-tools" -type f -name sdkmanager -print -quit)"
if [[ -z "$manager" ]]; then
  echo "sdkmanager not found under ${sdk}/cmdline-tools" >&2
  exit 1
fi

yes | "$manager" --sdk_root="$sdk" "platforms;android-${API_LEVEL}"
if [[ ! -d "$platform_dir" ]]; then
  echo "Failed to install platforms;android-${API_LEVEL} into ${sdk}" >&2
  exit 1
fi
echo "Installed platforms/android-${API_LEVEL} into ${sdk}"
