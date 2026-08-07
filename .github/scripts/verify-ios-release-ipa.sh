#!/usr/bin/env bash
# Verify a signed Release IPA without printing private key material.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 <ipa> <expected-team-id> <expected-bundle-id>" >&2
  exit 2
fi

ipa_path="$1"
expected_team_id="$2"
expected_bundle_id="$3"

if [[ ! -f "$ipa_path" ]]; then
  echo "IPA not found: $ipa_path" >&2
  exit 1
fi

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/verify-ios-ipa.XXXXXX")"
cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

unzip -q "$ipa_path" -d "$work_dir"
app_path="$(find "$work_dir/Payload" -maxdepth 1 -name '*.app' -type d | head -n 1)"
if [[ -z "$app_path" ]]; then
  echo "No .app found inside IPA Payload." >&2
  exit 1
fi

codesign --verify --deep --strict --verbose=2 "$app_path"

codesign_dump="$(codesign --display --verbose=4 "$app_path" 2>&1)"
team_id="$(printf '%s\n' "$codesign_dump" | awk -F= '/^TeamIdentifier=/{print $2; exit}')"
identifier="$(printf '%s\n' "$codesign_dump" | awk -F= '/^Identifier=/{print $2; exit}')"
authority="$(printf '%s\n' "$codesign_dump" | awk -F= '/^Authority=/{print $2; exit}')"

if [[ -z "$team_id" || -z "$identifier" ]]; then
  echo "Could not parse TeamIdentifier or Identifier from codesign output." >&2
  exit 1
fi

if [[ "$team_id" != "$expected_team_id" ]]; then
  echo "TeamIdentifier mismatch: got '$team_id', expected '$expected_team_id'." >&2
  exit 1
fi

if [[ "$identifier" != "$expected_bundle_id" ]]; then
  echo "Identifier mismatch: got '$identifier', expected '$expected_bundle_id'." >&2
  exit 1
fi

info_plist="$app_path/Info.plist"
short_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$info_plist")"
build_version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$info_plist")"

echo "IPA signature OK"
echo "  app=$(basename "$app_path")"
echo "  identifier=$identifier"
echo "  team=$team_id"
echo "  authority=$authority"
echo "  CFBundleShortVersionString=$short_version"
echo "  CFBundleVersion=$build_version"
