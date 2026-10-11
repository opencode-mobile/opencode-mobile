---
name: pull-app-logs
description: "App logs: use for bounded capture, live tailing, or runtime diagnosis on connected Android or iOS targets."
---

# Pull app logs

Capture logs from `dev.opencode.mobile` (bundle id, Android application id,
and iOS log subsystem; iOS process name `OpenCode Mobile`). Run the commands
yourself; ask the user only when no target exists or device-service access is
blocked.

**Target selection.** List Android devices and booted iOS simulators (and
connected iOS devices). Pick a target automatically only when exactly one is
connected or exactly one has the app running; otherwise list identifiers and
ask. Always pass the explicit serial or UDID, never `booted` or the first
device.

**Capture.** Prefer a bounded recent capture; tail live only when asked or
while the user reproduces a problem. If the app process is absent, capture
bounded history, label it historical, and do not infer current state from it.
If an app-scoped capture is empty, broaden the window once, verify the filter,
then report that no app logs were available. Do not substitute system noise.

**Report.** Target and identifier, capture mode and window, app running state,
findings tied to timestamps or short excerpts, app-owned errors before system
errors, and any evidence gap with the next capture that would close it. For
general diagnostics cover app running state, server reachability, foreground
event-stream state, active session evidence, and app warnings/errors.

## Android

```bash
adb devices -l
PID="$(adb -s "$SERIAL" shell pidof -s dev.opencode.mobile 2>/dev/null | tr -d '\r')"
adb -s "$SERIAL" logcat -d --pid="$PID" -t 300          # bounded
adb -s "$SERIAL" logcat -d --pid="$PID" -t 500 '*:W'    # warnings and errors
adb -s "$SERIAL" logcat --pid="$PID"                    # live
```

Never pass an empty PID. When the app is not running, build a tag filter from
current source (`TAG = "..."` and `Log.x("...")` under
`android/app/src/main/java`), then `adb logcat -d -t 2000 | grep -E "$FILTER"`.
Keep the filter quoted. The standard emulator reaches the host at `10.0.2.2`,
not `127.0.0.1`.

## iOS

```bash
xcrun simctl list devices booted
xcrun devicectl list devices
xcrun simctl spawn "$UDID" launchctl list | grep -F dev.opencode.mobile   # running?
xcrun simctl spawn "$UDID" log show --predicate 'subsystem == "dev.opencode.mobile"' \
  --style compact --last 10m --info --debug                             # bounded
xcrun simctl spawn "$UDID" log stream --predicate 'subsystem == "dev.opencode.mobile"' \
  --level debug --style compact                                         # live
xcrun simctl spawn "$UDID" log show \
  --predicate 'subsystem == "dev.opencode.mobile" AND (messageType == error OR messageType == fault)' \
  --style compact --last 30m --info                                     # errors
```

Keep `--info --debug`; event-stream and session state log below the default
level. The subsystem is defined in
`ios/OpenCode Mobile/OpenCode Mobile/OpenCodeLogging.swift`. For a physical
device use Xcode device logs or Console.app filtered by subsystem or process;
`devicectl` log commands vary by Xcode version, so check `xcrun devicectl help`
rather than inventing one. A simulator reaches the host at `127.0.0.1`; a
physical device does not.

If `adb`, CoreSimulatorService, or device services are blocked by the
environment, request device-service access through the harness permission flow
and retry once, then report the exact command and error.
