# Android log capture

## Identity and target discovery

- Application id: `dev.opencode.mobile`
- Source of truth: `android/app/build.gradle.kts`

```bash
adb devices -l
```

Select one target in state `device`. Use `-s "$SERIAL"` on every subsequent
`adb` command, even when only one device is connected.

**Completion criterion:** One usable Android serial is selected, and every
later command names that serial.

## Bounded process capture

Resolve the PID, then build the filtered command:

```bash
PID="$(adb -s "$SERIAL" shell pidof -s dev.opencode.mobile 2>/dev/null | tr -d '\r')"
if [ -n "$PID" ]; then
  adb -s "$SERIAL" logcat -d --pid="$PID" -t 300
else
  echo "OpenCode Mobile is not running on $SERIAL" >&2
fi
```

An empty PID means the app is not running. Use the historical fallback instead
of passing an empty value to `--pid`.

**Completion criterion:** The command returns at most 300 lines scoped to the
running app PID, or records that the app is not running.

## Historical fallback when the process is absent

Build the filter from current source. Do not keep a tag list in this skill:

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

Keep `$FILTER` quoted so `|`, spaces, or shell metacharacters are not
reinterpreted. Narrow the generated alternatives to tags relevant to the
user's symptom when the capture is noisy.

**Completion criterion:** The historical capture uses tags discovered from
current source, stays bounded, and is labeled historical because the app PID
was absent.

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

**Completion criterion:** The live stream is scoped to the selected serial and
current app PID, with only source-confirmed tags when tag filtering is used.

## Warning and error capture

```bash
PID="$(adb -s "$SERIAL" shell pidof -s dev.opencode.mobile 2>/dev/null | tr -d '\r')"
if [ -n "$PID" ]; then
  adb -s "$SERIAL" logcat -d --pid="$PID" -t 500 '*:W'
else
  echo "OpenCode Mobile is not running on $SERIAL" >&2
fi
```

**Completion criterion:** The capture contains at most 500 warning-or-higher
lines for the running app PID, or records that the app is not running.

## Networking interpretation

The standard Android emulator reaches a server on its host through `10.0.2.2`,
not emulator-local `127.0.0.1`. For physical devices and nonstandard virtual
devices, confirm the route before interpreting the server URL.
