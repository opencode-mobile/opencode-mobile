---
name: pull-app-logs
description: Capture and interpret OpenCode Mobile runtime logs. Use when the user asks for app or device logs, a live tail, or runtime diagnosis on connected Android or iOS targets.
---

# Pull App Logs

Capture evidence from `dev.opencode.mobile`; do not ask the user to run commands unless no usable target is available or device-service access remains blocked.

## Steps

### 1. Select the target

Detect Android devices, booted iOS simulators, and connected iOS devices. Honor an explicitly named platform or target. Otherwise select a target only when exactly one connected target exists, or exactly one target has the app running. For a cross-platform comparison, apply that rule independently to each platform. If multiple candidates remain, report their identifiers and ask the user to choose; do not silently use `booted` or the first device.

Read the applicable platform reference before capturing:

- [Android capture](ANDROID.md) for an Android device or emulator.
- [iOS capture](IOS.md) for an iOS simulator or physical device.

This step is complete when every requested platform maps to exactly one target identifier, or every unresolved ambiguity is shown to the user.

### 2. Capture app-scoped evidence

Prefer a bounded recent capture. Use a live tail only when the user asks for one or is reproducing a problem now. Scope output by the running app process on Android and by the app subsystem on iOS.

If the app process is absent, do not run a PID-filtered command. Capture bounded historical output using the platform fallback, label it as historical, and do not infer current app state from it. If an app-scoped capture is empty, broaden the time or line window once and verify the filter; then report that no app logs were available rather than substituting unrelated system noise.

This step is complete when each selected target has either app-scoped output with a stated interval/mode, or an explicit evidence gap: app not running, no matching output after one broadened capture, or access failure.

### 3. Analyze against the question

Trace the user's symptom through relevant events, preserving timestamps and error context. Separate observed facts from inference. Ignore platform noise unless it participates in the reported failure.

For general diagnostics, account for:

- app running state;
- server/API reachability;
- foreground event-stream state;
- active session evidence;
- app-owned warnings and errors.

This step is complete when every symptom or requested area has supporting log evidence, a clearly marked inference, or an explicit statement that the capture contains no evidence for it.

### 4. Report the evidence

Report:

1. target platform, name, and identifier;
2. capture mode and interval;
3. app running state;
4. findings tied to timestamps or concise log excerpts;
5. app-owned errors before relevant system errors;
6. evidence gaps and the next discriminating capture, if one is needed.

When comparing platforms, distinguish observed differences from expected networking differences. Do not offer a live tail unless reproduction would resolve a stated evidence gap.

This step is complete when every selected target and every requested symptom is accounted for in the report.

## Troubleshooting branch

When target discovery, device services, or filtering fails, read [connection and capture troubleshooting](TROUBLESHOOTING.md) and apply the matching branch. Do not load it for a successful capture.
