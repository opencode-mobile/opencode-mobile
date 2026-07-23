#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <aab> <apk> <expected-certificate-sha256>" >&2
  exit 2
fi

aab_path="$1"
apk_path="$2"

"$(dirname "$0")/verify-android-aab-signature.sh" "$aab_path" "$3"
"$(dirname "$0")/verify-android-apk-signature.sh" "$apk_path" "$3"
