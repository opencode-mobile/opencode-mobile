#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <aab> <expected-certificate-sha256>" >&2
  exit 2
fi

aab_path="$1"
expected_sha256="$(printf '%s' "$2" | tr -d ':' | tr '[:upper:]' '[:lower:]')"

if [[ ! -f "$aab_path" ]]; then
  echo "Release artifact not found: $aab_path" >&2
  exit 1
fi

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

echo "AAB signature verified with the expected upload certificate."
