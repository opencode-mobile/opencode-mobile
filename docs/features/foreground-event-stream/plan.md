# Foreground Event Stream

## User Goal

While the app is open, Session Chat and home reflect server activity in near real time.
After reconnect, state is refreshed from the server.

## In Scope

- `ForegroundEventStream` wrapping v1 `GET /global/event` **and** instance
  `GET /event`, plus v2 `GET /api/event`. All stay. Both protocols must
  carry relevant live events (session lifecycle, message/part, status,
  permissions, questions), not heartbeats only.
- Foreground lifecycle: connect on app foreground, disconnect on background.
- Reconnect with refresh of session list, statuses, and open chat messages.
- Session Chat incrementally merges relevant message events for the bound
  session and uses selective authoritative reloads for reconciliation.
- Home reloads session sections on session lifecycle events.
- Session status labels on home rows and Session Chat chrome (`Working`, `Retrying`).
- Screen Model connection states: `idle`, `connecting`, `connected`, `disconnected`, `reconnecting`.
- Device network path (`ready` / `unavailable`) composed with SSE for **live** /
  **connecting** / **offline** chrome. See ope-173-plan.md.

## Out Of Scope

- Permission request UI (events consumed; UI in a follow-on slice).
- Abort button UX.
- Diff viewer UI.
- Background delivery, push, or replacing SSE with broad polling. The chat
  timeline keeps a narrow changed-message watchdog poll as a temporary safety
  net.
- Connection Profile UI.

## Server APIs Involved

- `GET /global/event` — v1 SSE (desktop default). Must carry relevant events.
- `GET /event` — v1 instance SSE (directory-scoped). Carries `session.created`,
  `message.*`, status, permissions, questions. Keep it; do not treat as optional.
- `GET /api/event` — v2 SSE (live Session Chat shape). Same relevant-event bar.
- `GET /session/status` — baseline and reconnect status refresh.
- Existing `GET /session/:id/message` for initial history, selective
  reconciliation, reconnect, and the changed-message watchdog.

## Expected Logs

- The foreground stream records connection lifecycle, retry timing, response
  status, and parsed event type/session ID.
- `MessageSync` records message synchronization reasons and counts separately.
- Logs exclude message text, credentials, authorization headers, and full
  response bodies.

## Domain Objects

- `OpencodeEvent`, `EventStreamConnectionState`, `ForegroundEventStream`,
  `DeviceNetworkPath`, `MessageSync`, and `InMemoryMessageStore`.
- Reuses `SessionStatus`, `SessionSummary`, `MessageRepository`, `SessionRepository`.

## UI States

- Stream connection indicator is internal to Screen Models; no dedicated probe screen.
- Session rows and chat header may show optional status subtitle when session is busy or retrying.

## Error States

- Stream transport failure maps to `disconnected` / `reconnecting`; manual home
  refresh still works.
- Message synchronization keeps the last good snapshot when event-driven REST
  reconciliation fails; a failed idle reconciliation ends local streaming state
  so the watchdog can recover.

## Permission Behavior

Not applicable in this slice.

## Design-System Components

- Reuse `OpenCodeListItem` subtitle for session status on home.
- Reuse `OpenCodeTopBar` / existing chat chrome for Session Chat status line.

## Navigation Entry Points

- Android: app-level lifecycle in `MainActivity`; `MainScreenViewModel`, `SessionChatViewModel`.
- iOS: `scenePhase` in `OpenCode_MobileApp`; `ProjectListHomeViewModel`, `SessionChatViewModel`.
