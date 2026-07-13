# iOS Log Capture

## Identity and target discovery

- Bundle id and log subsystem: `dev.opencode.mobile`
- Process name: `OpenCode Mobile`
- Bundle-id source of truth: `ios/OpenCode Mobile/OpenCode Mobile.xcodeproj/project.pbxproj`
- Logging source of truth: `ios/OpenCode Mobile/OpenCode Mobile/OpenCodeLogging.swift`

Discover booted simulators and connected physical devices separately:

```bash
xcrun simctl list devices booted
xcrun devicectl list devices
```

Do not use the `booted` alias when more than one simulator is booted. Substitute the selected simulator's UDID in every command.

Check whether the selected simulator currently exposes an app service:

```bash
APP_SERVICE="$(
  xcrun simctl spawn "$UDID" launchctl list 2>/dev/null |
    grep -F 'dev.opencode.mobile' || true
)"
if [ -n "$APP_SERVICE" ]; then
  echo "OpenCode Mobile is running on $UDID"
else
  echo "OpenCode Mobile is not running on $UDID"
fi
```

The subsystem history remains useful when the process is absent, but it is historical evidence and does not establish current state.

## Bounded simulator capture

```bash
xcrun simctl spawn "$UDID" log show \
  --predicate 'subsystem == "dev.opencode.mobile"' \
  --style compact --last 10m --info --debug
```

The predicate is single-quoted so the shell passes it as one expression. Keep `--info --debug`; relevant event-stream and session state can be below the default level.

If the result is empty, broaden `--last` once (for example, to `30m`) and verify the subsystem against `OpenCodeLogging.swift` before reporting no matching output.

## Live simulator capture

```bash
xcrun simctl spawn "$UDID" log stream \
  --predicate 'subsystem == "dev.opencode.mobile"' \
  --level debug --style compact
```

## Error and fault capture

```bash
xcrun simctl spawn "$UDID" log show \
  --predicate 'subsystem == "dev.opencode.mobile" AND (messageType == error OR messageType == fault)' \
  --style compact --last 30m --info
```

## Physical device capture

Prefer Xcode's device logs or Console.app, filtering by subsystem `dev.opencode.mobile` or process `OpenCode Mobile`. Because `devicectl` log commands vary by installed Xcode version, inspect the locally available command before using it:

```bash
xcrun devicectl help
```

Use only a log command documented by that output, and target the selected device identifier explicitly. If the local CLI exposes no suitable stream or archive command, report that limitation and use Xcode or Console rather than inventing a command.

## Networking interpretation

An iOS simulator can normally reach a host server through `127.0.0.1`. A physical device cannot; interpret its configured server URL according to the actual LAN, VPN, HTTPS, or tunnel route.
