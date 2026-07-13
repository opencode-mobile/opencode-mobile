# Connection and Capture Troubleshooting

## Device tooling is unavailable

- Missing `adb`: locate the Android SDK platform tools or report that Android capture is unavailable.
- Missing `xcrun`, `simctl`, or `devicectl`: iOS capture requires macOS with the matching Xcode tooling.
- Linux environment: Android devices may be available; iOS simulator and device tooling is not.

## Device service access is blocked

If the execution environment blocks `adb`, CoreSimulatorService, or physical-device services, request the environment's device-service or full host-tool access using its current permission mechanism. Do not assume a particular permission field or syntax. Retry discovery once after access is granted; if it still fails, include the exact command and error in the report.

## Android target failures

- `adb devices -l` shows no usable target: ask the user to start an emulator or connect a device with USB debugging enabled.
- Target is `offline` or `unauthorized`: report that state; do not attempt capture until it becomes `device`.
- App PID is absent: use the bounded historical fallback in [ANDROID.md](ANDROID.md), label it historical, and ask for a reproduction only if recent logs do not answer the question.
- Filtered history is empty: verify tags from current source, broaden the bounded line count once, then report no matching output.

## iOS target failures

- No booted simulator: a connected physical device is a separate candidate; otherwise ask the user to boot a simulator.
- CoreSimulatorService error: confirm Xcode/Simulator is running and retry once with the environment's required host-tool access.
- Empty subsystem capture: keep `--info --debug`, verify the subsystem in `OpenCodeLogging.swift`, broaden the time window once, then report no matching output.
- Multiple booted simulators or connected devices: list identifiers and ask the user to choose unless exactly one is confirmed to be running the app.

## Capture contains only system noise

Do not treat unrelated platform logs as app evidence. Recheck the Android PID/source-derived tag filter or the iOS subsystem predicate. If the app scope is correct and still empty, report the evidence gap and propose a live reproduction capture.
