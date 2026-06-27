#!/usr/bin/env bash
set -euo pipefail

echo "This script applies two one-time macOS fixes for opencode-mobile:"
echo "  1. Point xcode-select at Xcode.app (not Command Line Tools only)"
echo "  2. Register Homebrew JDK 17 with /usr/libexec/java_home"
echo

if ! [ -d "/Applications/Xcode.app/Contents/Developer" ]; then
  echo "error: Xcode.app not found at /Applications/Xcode.app" >&2
  exit 1
fi

if ! [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk" ]; then
  echo "error: openjdk@17 not found. Install with: brew install openjdk@17" >&2
  exit 1
fi

echo "Running (will prompt for your password)..."
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
sudo mkdir -p /Library/Java/JavaVirtualMachines
sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk

echo
echo "Done. Verifying..."
xcode-select -p
/usr/libexec/java_home -V

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
"$SCRIPT_DIR/verify-env.sh"
