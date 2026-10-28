#!/usr/bin/env bash
# Export Apple's iPhone Duo device frames from the local Xcode as SVG for
# prototypes. Needs Xcode 27.1+ (DeviceKit chrome phone14/phone15) and
# pdftocairo (poppler). The repository does not ship Apple's artwork.
#
# usage: scripts/iphone-duo-frames.sh <out-dir>
# Geometry: docs/devices/iphone-duo.md and docs/devices/iphone-duo/geometry.json
set -euo pipefail

out="${1:?usage: $0 <out-dir>}"
chrome=/Library/Developer/DeviceKit/Chrome

command -v pdftocairo >/dev/null || { echo "pdftocairo not found (brew install poppler)" >&2; exit 1; }
for name in phone15 phone14; do
  [ -f "$chrome/$name.devicechrome/Contents/Resources/PhoneComposite.pdf" ] || {
    echo "Missing $chrome/$name.devicechrome; install Xcode 27.1 or later" >&2
    exit 1
  }
done

mkdir -p "$out"
# Outer display: 514×706, screen at (26, 14).
pdftocairo -svg "$chrome/phone15.devicechrome/Contents/Resources/PhoneComposite.pdf" "$out/iphone-duo-outer.svg"
# Inner display: 660×924 composite with 17pt margins; scale by 669/626.
pdftocairo -svg "$chrome/phone14.devicechrome/Contents/Resources/PhoneComposite.pdf" "$out/iphone-duo-inner.svg"
echo "Wrote $out/iphone-duo-outer.svg and $out/iphone-duo-inner.svg"
