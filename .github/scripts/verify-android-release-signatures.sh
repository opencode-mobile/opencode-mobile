#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <aab> <apk> <expected-certificate-sha256>" >&2
  exit 2
fi

aab_path="$1"
apk_path="$2"
expected_sha256="$(printf '%s' "$3" | tr -d ':' | tr '[:upper:]' '[:lower:]')"

for artifact_path in "$aab_path" "$apk_path"; do
  if [[ ! -f "$artifact_path" ]]; then
    echo "Release artifact not found: $artifact_path" >&2
    exit 1
  fi
done

if ! jarsigner -verify -certs "$aab_path" >/dev/null 2>&1; then
  echo "AAB signature verification failed." >&2
  jarsigner -verify -certs "$aab_path"
  exit 1
fi

aab_sha256="$({ keytool -printcert -jarfile "$aab_path"; } | awk -F': ' '/SHA256:/{print $NF; exit}' | tr -d ':' | tr '[:upper:]' '[:lower:]')"
if [[ -z "$aab_sha256" || "$aab_sha256" != "$expected_sha256" ]]; then
  echo "AAB signer does not match the expected upload certificate." >&2
  exit 1
fi

apksigner_path="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -print | sort -V | tail -n 1)"
if [[ -z "$apksigner_path" ]]; then
  echo "apksigner was not found in Android build-tools." >&2
  exit 1
fi

apk_verification="$($apksigner_path verify --verbose --print-certs "$apk_path")"
apk_sha256="$(printf '%s\n' "$apk_verification" | awk -F': ' '/certificate SHA-256 digest:/{print $NF; exit}')"
if [[ -z "$apk_sha256" || "$apk_sha256" != "$expected_sha256" ]]; then
  echo "APK signer does not match the expected upload certificate." >&2
  exit 1
fi

echo "AAB signature verified with the expected upload certificate."
echo "APK signature verified with the expected upload certificate."
