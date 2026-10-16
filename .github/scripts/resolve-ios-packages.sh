#!/usr/bin/env bash
# Resolve Swift packages for the OpenCode Mobile Xcode project.
# Run from ios/OpenCode Mobile.
#
# A cancelled job on the shared macOS runner can leave DerivedData claiming
# Firebase binary artifacts (absl.zip, openssl_grpc.zip) that are no longer
# on disk. Retry once after deleting this project's SourcePackages cache.
set -euo pipefail

resolve() {
  xcodebuild -resolvePackageDependencies \
    -project "OpenCode Mobile.xcodeproj" \
    -scheme "OpenCode Mobile"
}

clear_source_packages() {
  shopt -s nullglob
  local dir
  for dir in "${HOME}/Library/Developer/Xcode/DerivedData"/OpenCode_Mobile-*/SourcePackages; do
    echo "Removing stale SourcePackages cache: ${dir}"
    rm -rf "${dir}"
  done
}

if resolve; then
  exit 0
fi

echo "Package resolve failed. Clearing the SourcePackages cache and retrying once."
clear_source_packages
resolve
