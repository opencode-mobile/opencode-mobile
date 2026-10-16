#!/usr/bin/env bash
set -euo pipefail

# Installs an Android SDK platform when a self-hosted runner does not have it.
# API 37 and later are published as platforms;android-<level>.0, not android-<level>.
API_LEVEL="${1:?usage: ensure-android-platform.sh <api-level>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CMDLINE_TOOLS_VERSION="${ANDROID_CMDLINE_TOOLS_VERSION:-14742923}"

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" && -f "${ROOT}/android/local.properties" ]]; then
  sdk="$(sed -n 's/^sdk.dir=//p' "${ROOT}/android/local.properties" | head -1)"
fi
if [[ -z "$sdk" || ! -d "$sdk" ]]; then
  echo "Android SDK not found. Set ANDROID_HOME or android/local.properties sdk.dir." >&2
  exit 1
fi

if [[ "$API_LEVEL" == *.* ]]; then
  package="platforms;android-${API_LEVEL}"
  dir_names=("android-${API_LEVEL}")
elif [[ "$API_LEVEL" -ge 37 ]]; then
  package="platforms;android-${API_LEVEL}.0"
  dir_names=("android-${API_LEVEL}.0" "android-${API_LEVEL}")
else
  package="platforms;android-${API_LEVEL}"
  dir_names=("android-${API_LEVEL}")
fi

platform_installed() {
  local name
  for name in "${dir_names[@]}"; do
    if [[ -d "${sdk}/platforms/${name}" ]]; then
      echo "platforms/${name} already installed at ${sdk}"
      return 0
    fi
  done
  return 1
}

if platform_installed; then
  exit 0
fi

pick_manager() {
  local candidate
  for candidate in \
    "${sdk}/cmdline-tools/latest/bin/sdkmanager" \
    "${sdk}/cmdline-tools/latest-2/bin/sdkmanager"
  do
    if [[ -f "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  if [[ -d "${sdk}/cmdline-tools" ]]; then
    find "${sdk}/cmdline-tools" -type f -name sdkmanager -print -quit || true
  fi
}

install_cmdline_tools() {
  local host tmp
  case "$(uname -s)" in
    Darwin) host=mac ;;
    Linux) host=linux ;;
    *)
      echo "Cannot download cmdline-tools on $(uname -s)" >&2
      return 1
      ;;
  esac
  tmp="$(mktemp -d)"
  curl -fsSL -o "${tmp}/cmdtools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-${host}-${CMDLINE_TOOLS_VERSION}_latest.zip"
  unzip -q "${tmp}/cmdtools.zip" -d "${tmp}/cmdtools"
  mkdir -p "${sdk}/cmdline-tools"
  rm -rf "${sdk}/cmdline-tools/latest"
  mv "${tmp}/cmdtools/cmdline-tools" "${sdk}/cmdline-tools/latest"
  rm -rf "${tmp}"
  echo "Installed Android cmdline-tools ${CMDLINE_TOOLS_VERSION} into ${sdk}/cmdline-tools/latest"
}

run_sdkmanager() {
  local manager="$1"
  local log
  log="$(mktemp)"
  set +o pipefail
  yes | "$manager" --sdk_root="$sdk" "$package" >"$log" 2>&1 || true
  set -o pipefail
  cat "$log"
  if grep -q "Failed to find package" "$log"; then
    rm -f "$log"
    return 1
  fi
  rm -f "$log"
}

manager="$(pick_manager || true)"
if [[ -z "$manager" ]]; then
  install_cmdline_tools
  manager="$(pick_manager)"
fi

if ! run_sdkmanager "$manager"; then
  echo "sdkmanager could not see ${package}; installing current cmdline-tools and retrying" >&2
  install_cmdline_tools
  manager="$(pick_manager)"
  run_sdkmanager "$manager"
fi

if platform_installed; then
  exit 0
fi
echo "Failed to install ${package} into ${sdk}" >&2
exit 1
