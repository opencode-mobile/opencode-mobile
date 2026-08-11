# Diagnostic Logs

Linear: [OPE-104](https://linear.app/opencode-mobile/issue/OPE-104/share-diagnostic-logs)

## User Goal

As a developer using the mobile app, capture and share a redacted rolling
diagnostic log from Settings so live session issues (missed permission prompts,
stuck busy state, SSE drops) can be diagnosed without adb or Xcode.

## In Scope

- Always-on rolling buffer with a **5-minute** primary time window, mirrored to
  a local JSONL file so the window survives app restart / process death.
- Silent soft safety cap of **2,000 entries** (drop oldest); time eviction stays
  primary.
- Structured lines: timestamp, area, level, message (and optional opaque ids /
  status codes).
- App logging facade feeds both the platform sink and the buffer. Do not scrape
  logcat or `os_log`.
- Hard redaction at write time: no passwords, tokens, Basic Auth; no prompt or
  message text; no file or diff bodies; no permission command patterns.
- Always-on areas for v1: `sse`, `permission`, `question`, `session`, `hang`
  (plus closely related stuck-session signals such as reconnect and permission /
  question refresh outcomes). `hang` is written by the main-thread hang
  watchdog (OPE-134).
- Settings → Developer Tools → Diagnostic Logs: share last window as `.jsonl`
  via the system share sheet; Clear buffer with confirmation.
- iOS and Android parity for buffer policy, areas, redaction, and Settings
  actions. Reuse the TextMate Logbook *pattern* (bounded store + Developer
  Tools), not its fence-highlighting content.

## Out Of Scope

- Full logcat / `os_log` mirror.
- Crash upload or always-on remote telemetry.
- Push notifications or background capture beyond the persisted foreground
  window.
- Expanding TextMate Logbook into this surface (keep separate; optional later
  share for TextMate JSONL).
- Surfacing MB or entry-count knobs in the UI.

## Server APIs Involved

None for the log buffer or Settings export itself. Instrumentation observes
existing client behavior around:

- Foreground SSE (`/global/event`) lifecycle and reconnect.
- Permission and question refresh / event handling (`permission.asked`,
  reconnect → `GET /permission`, question equivalents).
- Session status / busy transitions.

## Expected Platform Logs

High-value lines should land in both the platform sink and the diagnostic
buffer, for example:

- SSE start / stop / reconnect / open status / parse failure / stream failure.
- Permission or question refresh outcome (asked, empty, error) without command
  patterns or prompt text.
- Session status / busy transitions that explain a stuck UI.
- Opaque session / permission / question ids and HTTP status codes only.

## Domain Objects

- `DiagnosticLogArea` — `sse`, `permission`, `question`, `session`, `hang`, …
- `DiagnosticLogLevel` — at least `debug`, `info`, `warning`, `error`.
- `DiagnosticLogEntry` — timestamp, area, level, message (redacted).
- `DiagnosticLogBuffer` — append, snapshot (time window), clear; enforces
  5-minute eviction and 2,000-entry soft cap; persists the window to local
  JSONL and reloads on launch.
- App logging facade that dual-writes to platform logging and the buffer.
- Export snapshot metadata should note truncation / dropped count when the soft
  cap dropped lines inside the window.

## UI States

- Developer Tools idle (Diagnostic Logs row visible).
- Diagnostic Logs detail or action sheet: Share available; Clear available.
- Share sheet presenting a temporary `.jsonl` file.
- Clear confirmation visible.
- Empty buffer: Share still allowed (empty or header-only file) or disabled with
  clear empty copy — prefer share of header + empty body so support sees the
  window policy.

## User Actions And Events

- `openDiagnosticLogs` — open the Diagnostic Logs surface from Developer Tools.
- `shareDiagnosticLogs` — snapshot buffer → write temp `.jsonl` → system share.
- `clearDiagnosticLogsTapped` — show confirmation.
- `clearDiagnosticLogsConfirmed` / `clearDiagnosticLogsCancelled`.

## Error States

- Share write failure: surface a short error; leave buffer intact.
- Clear is local and best-effort; no remote side effects.
- Soft-cap truncation is not an error; export metadata records dropped count.

## Permission Behavior

Not applicable (no OS permission prompt for this slice beyond the system share
sheet).

## Design-System Components Needed

- Reuse `OpenCodeListItem` for Developer Tools rows and Diagnostic Logs actions.
- Reuse `OpenCodeDialog` (or platform equivalent already used by Clear Data /
  TextMate Logbook) for Clear confirmation.
- Share plumbing is intentionally feature-local (temporary file + system share
  sheet); no new reusable design-system component planned.

## Platform Entry Points

- iOS: Settings → Developer Tools → Diagnostic Logs → Share / Clear.
- Android: Settings → Developer Tools → Diagnostic Logs → Share / Clear.

## Implementation Phases

1. **Spec locked** — this plan and `decisions.md`.
2. **Core buffer** — facade + buffer + redaction tests on both platforms.
3. **Instrumentation** — route high-value SSE / permission / question / session
   paths through the facade.
4. **Settings UI** — Share `.jsonl` + Clear with confirmation.
5. **Validation** — reproduce stuck session / pending permission; confirm export
   evidence without secrets; parity check.
