# iOS log capture

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

Use the selected simulator's UDID in every command. Do not use the `booted`
alias when more than one simulator is booted.

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

Subsystem history still helps when the process is absent. Treat it as historical. It does not prove current state.

**Completion criterion:** One simulator UDID or physical-device identifier is
selected, and running state is observed or explicitly unknown. Every simulator
command names the selected UDID.

## Bounded simulator capture

```bash
xcrun simctl spawn "$UDID" log show \
  --predicate 'subsystem == "dev.opencode.mobile"' \
  --style compact --last 10m --info --debug
```

The predicate is single-quoted so the shell passes it as one expression. Keep `--info --debug`; relevant event-stream and session state can be below the default level.

If the result is empty, broaden `--last` once, for example to `30m`, and verify the subsystem against `OpenCodeLogging.swift` before reporting no matching output.

**Completion criterion:** The selected simulator has bounded subsystem output
with its time window stated, or no matching output remains after one broadened
capture and subsystem verification.

## Live simulator capture

```bash
xcrun simctl spawn "$UDID" log stream \
  --predicate 'subsystem == "dev.opencode.mobile"' \
  --level debug --style compact
```

**Completion criterion:** The live stream is scoped to the selected simulator
UDID and subsystem `dev.opencode.mobile` at debug level.

## Error and fault capture

```bash
xcrun simctl spawn "$UDID" log show \
  --predicate 'subsystem == "dev.opencode.mobile" AND (messageType == error OR messageType == fault)' \
  --style compact --last 30m --info
```

**Completion criterion:** The result contains only error or fault history for
the selected simulator and app subsystem within the stated 30-minute window.

## Physical device capture

Prefer Xcode's device logs or Console.app, filtering by subsystem `dev.opencode.mobile` or process `OpenCode Mobile`. `devicectl` log commands vary by installed Xcode version, so inspect the locally available command before using it:

```bash
xcrun devicectl help
```

Use only a log command documented by that output. Target the selected device identifier. If the local CLI exposes no suitable stream or archive command, say so and use Xcode or Console. Do not invent a command.

**Completion criterion:** Physical-device logs come from a command documented
by the installed Xcode, Xcode device logs, or Console.app, and the capture is
scoped to the selected device and app.

## Networking interpretation

An iOS simulator can normally reach a host server through `127.0.0.1`. A
physical device cannot. Interpret its configured server URL through the actual
LAN, VPN, HTTPS, or tunnel route.
