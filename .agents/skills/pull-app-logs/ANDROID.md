# Android Log Capture

## Identity and target discovery

- Application id: `dev.opencode.mobile`
- Source of truth: `android/app/build.gradle.kts`

```bash
adb devices -l
```

Use `-s "$SERIAL"` on every subsequent `adb` command, even when only one device is currently connected.

## Bounded process capture

Resolve the PID before constructing a filtered command:

```bash
PID="$(adb -s "$SERIAL" shell pidof -s dev.opencode.mobile 2>/dev/null | tr -d '\r')"
if [ -n "$PID" ]; then
  adb -s "$SERIAL" logcat -d --pid="$PID" -t 300
else
  echo "OpenCode Mobile is not running on $SERIAL" >&2
fi
```

An empty PID means the app is not running; it is not a valid argument to `--pid`.

## Historical fallback when the process is absent

Build the filter from current source instead of maintaining a tag list in this skill:

```bash
FILTER="$(
  {
    rg --no-filename --only-matching 'TAG\s*=\s*"[^"]+"' android/app/src/main/java
    rg --no-filename --only-matching 'Log\.[vdiew]\("[^"]+"' android/app/src/main/java
  } |
    sed -E 's/.*"([^"]+)".*/\1/' |
    sort -u |
    sed 's/[][\\.^$*+?(){}|]/\\&/g' |
    paste -sd'|' -
)"
```

Then capture a bounded buffer with the discovered filter:

```bash
if [ -z "$FILTER" ]; then
  echo "No Android log tags found in current source" >&2
  exit 1
fi
adb -s "$SERIAL" logcat -d -t 2000 | grep -E "$FILTER" | tail -300
```

Keep `$FILTER` quoted so `|`, spaces, or shell metacharacters are not reinterpreted. Narrow the generated alternatives to tags relevant to the user's symptom when the capture is noisy.

## Live process capture

```bash
PID="$(adb -s "$SERIAL" shell pidof -s dev.opencode.mobile 2>/dev/null | tr -d '\r')"
if [ -z "$PID" ]; then
  echo "OpenCode Mobile is not running on $SERIAL" >&2
  exit 1
fi
adb -s "$SERIAL" logcat --pid="$PID"
```

For a source-confirmed tag filter, pass each filter specification as a quoted argument and silence everything else. The placeholders below must be replaced by tags found in current source:

```bash
adb -s "$SERIAL" logcat --pid="$PID" '<tag-one>:V' '<tag-two>:V' '*:S'
```

## Warning and error capture

```bash
PID="$(adb -s "$SERIAL" shell pidof -s dev.opencode.mobile 2>/dev/null | tr -d '\r')"
if [ -n "$PID" ]; then
  adb -s "$SERIAL" logcat -d --pid="$PID" -t 500 '*:W'
else
  echo "OpenCode Mobile is not running on $SERIAL" >&2
fi
```

## Networking interpretation

The standard Android emulator reaches a server on its host through `10.0.2.2`, not emulator-local `127.0.0.1`. Do not apply this rule to physical devices or nonstandard virtual-device networking without confirming their route.
