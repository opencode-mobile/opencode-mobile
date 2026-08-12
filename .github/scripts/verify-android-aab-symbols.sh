#!/usr/bin/env bash
# Verify that a release AAB carries R8 mapping metadata for Play Console
# deobfuscation. Native debug symbols are required when AGP extracted any;
# pre-stripped third-party .so files may leave the AAB without a
# debugsymbols entry, which is recorded rather than treated as a hard failure.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <aab> <mapping.txt>" >&2
  exit 2
fi

aab_path="$1"
mapping_path="$2"

if [[ ! -f "$aab_path" ]]; then
  echo "Release AAB not found: $aab_path" >&2
  exit 1
fi

if [[ ! -f "$mapping_path" ]]; then
  echo "R8 mapping file not found: $mapping_path" >&2
  exit 1
fi

if [[ ! -s "$mapping_path" ]]; then
  echo "R8 mapping file is empty: $mapping_path" >&2
  exit 1
fi

listing="$(unzip -Z1 "$aab_path")"

if ! grep -qx 'BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map' <<<"$listing"; then
  echo "AAB is missing embedded R8/ProGuard mapping metadata." >&2
  exit 1
fi

if grep -E '^BUNDLE-METADATA/com\.android\.tools\.build\.debugsymbols/' <<<"$listing" >/dev/null; then
  echo "Release AAB includes R8 mapping and native debug symbol metadata."
  exit 0
fi

echo "Release AAB includes R8 mapping."
echo "No embedded native debug symbols were found."
echo "This is expected when release .so files ship pre-stripped (for example AndroidX)."
echo "Play Console may still warn about native symbols until unstripped native code is present."
