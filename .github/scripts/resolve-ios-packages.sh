#!/usr/bin/env bash
# Resolve Swift packages for the OpenCode Mobile Xcode project.
# Run from ios/OpenCode Mobile.
#
# A cancelled job on the shared macOS runner can leave DerivedData claiming
# Firebase binary artifacts (absl.zip, openssl_grpc.zip) that are no longer
# on disk, and can leave half-written clones in SwiftPM's shared repository
# cache. Retry once after deleting both, cloning fresh instead of from the
# shared cache.
set -euo pipefail

resolve() {
  xcodebuild -resolvePackageDependencies \
    -project "OpenCode Mobile.xcodeproj" \
    -scheme "OpenCode Mobile" \
    "$@"
}

clear_source_packages() {
  shopt -s nullglob
  local dir
  for dir in "${HOME}/Library/Developer/Xcode/DerivedData"/OpenCode_Mobile-*/SourcePackages; do
    echo "Removing stale SourcePackages cache: ${dir}"
    rm -rf "${dir}"
  done
}

clear_repository_cache() {
  local dir="${HOME}/Library/Caches/org.swift.swiftpm/repositories"
  if [[ -d "${dir}" ]]; then
    echo "Removing SwiftPM repository cache: ${dir}"
    rm -rf "${dir}"
  fi
}

if resolve; then
  exit 0
fi

echo "Package resolve failed. Clearing package caches and retrying once without the shared cache."
clear_source_packages
clear_repository_cache
resolve -disablePackageRepositoryCache
