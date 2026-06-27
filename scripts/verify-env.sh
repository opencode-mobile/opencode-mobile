#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

pass() { printf '  OK  %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; FAIL=1; }

check_cmd() {
  if command -v "$1" >/dev/null 2>&1; then
    pass "$1 ($(command -v "$1"))"
  else
    fail "$1 not found on PATH"
  fi
}

echo "== Java =="
if command -v java >/dev/null 2>&1; then
  pass "java ($(java -version 2>&1 | head -1))"
else
  fail "java not found on PATH"
fi

if [ -n "${JAVA_HOME:-}" ]; then
  pass "JAVA_HOME=$JAVA_HOME"
else
  fail "JAVA_HOME is unset (enable jenv export plugin or set JAVA_HOME)"
fi

if /usr/libexec/java_home -V >/dev/null 2>&1; then
  pass "macOS java_home ($( /usr/libexec/java_home 2>/dev/null ))"
else
  fail "macOS java_home unavailable (current: none registered)"
  echo "       fix: sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk"
fi

if command -v jenv >/dev/null 2>&1; then
  pass "jenv ($(jenv version-name 2>/dev/null || echo unknown))"
else
  fail "jenv not found (brew install jenv)"
fi

echo
echo "== Xcode =="
XCODE_SELECT_PATH="$(xcode-select -p 2>/dev/null || true)"
if [[ "$XCODE_SELECT_PATH" == *"Xcode.app/Contents/Developer" ]]; then
  pass "xcode-select -> $XCODE_SELECT_PATH"
else
  fail "xcode-select -> ${XCODE_SELECT_PATH:-<unset>}"
  echo "       fix: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
fi
check_cmd xcodebuild

echo
echo "== Android =="
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ]; then
  pass "ANDROID_HOME=$ANDROID_HOME"
else
  fail "ANDROID_HOME missing or invalid"
fi
check_cmd android

echo
echo "== Node =="
check_cmd node
check_cmd npm

echo
echo "== Repo =="
if [ -f "$ROOT_DIR/.java-version" ]; then
  pass ".java-version -> $(cat "$ROOT_DIR/.java-version")"
else
  fail "missing .java-version at repo root"
fi

if [ -L "$ROOT_DIR/.cursor/skills" ] && [ -e "$ROOT_DIR/.cursor/skills" ]; then
  pass "agent symlinks (.cursor/skills)"
else
  fail "agent symlinks missing (run: ./scripts/sync-agent-links.sh)"
fi

echo
if [ "$FAIL" -eq 0 ]; then
  echo "Environment looks good."
else
  echo "Some checks failed. Fix the items above, then re-run: ./scripts/verify-env.sh"
  exit 1
fi
