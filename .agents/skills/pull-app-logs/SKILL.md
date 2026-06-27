---
name: pull-app-logs
description: Pull and summarize runtime logs from the opencode-mobile Android and iOS apps on connected devices or simulators. Use when the user asks for app logs, logcat, device logs, simulator logs, debugging runtime behavior, or investigating API/SSE/session-chat issues on a device.
---

# Pull App Logs

Read runtime logs for **OpenCode Mobile** on connected hardware. Both apps share bundle/application id `dev.opencode.mobile`.

Run commands yourself — do not tell the user to run them unless a device is missing or access fails.

## Workflow

1. Detect what is connected.
2. Pull recent logs for the running app process.
3. Filter to app-owned tags/subsystems (skip system noise unless needed).
4. Summarize: connection state, active session, errors, and anything relevant to the user's question.

```text
Task progress:
- [ ] Detect Android device/emulator
- [ ] Detect iOS simulator or physical device
- [ ] Pull app-scoped logs
- [ ] Summarize findings
```

## Detect devices

**Android**

```bash
adb devices -l
```

**iOS simulator**

```bash
xcrun simctl list devices booted
```

**iOS physical device**

```bash
xcrun devicectl list devices
```

On macOS, `simctl` and `adb` need unsandboxed shell access (`required_permissions: ["all"]`) when CoreSimulatorService or adb is blocked.

---

## Android

### App identity

| Field | Value |
|---|---|
| Application id | `dev.opencode.mobile` |
| Source | `android/app/build.gradle.kts` |

### Pull recent logs (preferred)

Filter by process PID so output is app-only:

```bash
PID=$(adb shell pidof -s dev.opencode.mobile 2>/dev/null)
if [ -n "$PID" ]; then
  adb logcat -d --pid="$PID" -t 300
else
  echo "App not running"
  adb logcat -d -t 500 | grep -iE "opencode|dev\.opencode\.mobile" | tail -100
fi
```

### Live tail

```bash
adb logcat --pid=$(adb shell pidof -s dev.opencode.mobile)
```

### Filter by log tag

App-defined tags (see `android/app/src/main/java/`):

| Tag | Area |
|---|---|
| `OpencodeApi` | HTTP requests/responses |
| `OpencodeSse` | Foreground SSE stream |
| `SessionChat` | Chat screen / ViewModel |
| `MessageRepo` | Message fetch |
| `MessageLiveStore` | Live message store |
| `MarkdownMapper` | Markdown enrichment |
| `ThemeManager` | Theme preset load/apply |

```bash
adb logcat --pid=$(adb shell pidof -s dev.opencode.mobile) \
  OpencodeApi:* OpencodeSse:* SessionChat:* MessageRepo:* MessageLiveStore:* ThemeManager:* *:S
```

### Errors only

```bash
adb logcat -d --pid=$(adb shell pidof -s dev.opencode.mobile) *:E *:W
```

### Emulator networking note

The Android emulator reaches the host opencode server at `http://10.0.2.2:4096`, not `127.0.0.1`.

---

## iOS

### App identity

| Field | Value |
|---|---|
| Bundle id | `dev.opencode.mobile` |
| Process name | `OpenCode Mobile` |
| OSLog subsystem | `dev.opencode.mobile` |
| Source | `ios/OpenCode Mobile/OpenCode Mobile/OpenCodeLogging.swift` |

### Pull recent logs (preferred)

Filter by subsystem. Include `--info --debug` — debug logs carry SSE heartbeats and session-chat state:

```bash
xcrun simctl spawn booted log show \
  --predicate 'subsystem == "dev.opencode.mobile"' \
  --style compact --last 10m --info --debug
```

If multiple simulators are booted, target one explicitly:

```bash
xcrun simctl spawn <UDID> log show \
  --predicate 'subsystem == "dev.opencode.mobile"' \
  --style compact --last 10m --info --debug
```

### Live tail

```bash
xcrun simctl spawn booted log stream \
  --predicate 'subsystem == "dev.opencode.mobile"' \
  --level debug
```

### Log categories

| Category | Area |
|---|---|
| `dev.opencode.mobile:api` | HTTP requests/responses/failures |
| `dev.opencode.mobile:sse` | Foreground SSE stream |
| `dev.opencode.mobile:session-chat` | Chat bind/observe/state |

### Errors and warnings

```bash
xcrun simctl spawn booted log show \
  --predicate 'subsystem == "dev.opencode.mobile" AND (messageType == error OR messageType == fault)' \
  --style compact --last 30m --info
```

### Simulator networking note

The iOS simulator uses `http://127.0.0.1:4096` for the host opencode server.

### Physical device

When a device is connected, prefer **Console.app** or stream via Xcode. CLI option:

```bash
xcrun devicectl device info logs --device <UDID>
```

Filter in Console by subsystem `dev.opencode.mobile` or process `OpenCode Mobile`.

---

## Summarize for the user

After pulling logs, report:

1. **Device** — emulator/simulator model or physical device name
2. **App state** — running or not; PID on Android
3. **Connection** — server URL, SSE state (Connected / Reconnecting / failed)
4. **Session** — active session id and message count if visible in logs
5. **Errors** — app errors first; ignore emulator/simulator system noise (HWUI, FrameTracker, SecTrustReportNetworkingAnalytics) unless the user asked about rendering or launch metrics
6. **Parity** — when comparing platforms, note URL differences (`10.0.2.2` vs `127.0.0.1`) and that Android may show SSE socket-timeout reconnect loops while iOS often stays connected with heartbeats

Offer to tail live logs if the user is actively reproducing a bug.

## Troubleshooting

| Problem | Fix |
|---|---|
| `adb: no devices` | Start emulator or connect device; enable USB debugging |
| Android app not running | Launch app first, or grep logcat without PID filter |
| `CoreSimulatorService connection became invalid` | Re-run with full permissions; ensure Xcode/simulator is running |
| No booted iOS simulator | Boot one in Xcode or `xcrun simctl boot <UDID>` |
| Empty iOS subsystem logs | Add `--info --debug`; default `log show` hides debug-level entries |
| Linux VM (Cursor Cloud) | Android logs only — iOS simulators require macOS + Xcode |
