# Foreground Event Stream

## User Goal

While the app is open, Session Chat and home reflect server activity in near real time.
After reconnect, state is refreshed from the server.

## In Scope

- `ForegroundEventStream` domain service wrapping `GET /global/event` SSE.
- Foreground lifecycle: connect on app foreground, disconnect on background.
- Reconnect with refresh of session list, statuses, and open chat messages.
- Session Chat reloads messages on relevant bus events for the bound session.
- Home reloads session sections on session lifecycle events.
- Session status labels on home rows and Session Chat chrome (`Working`, `Retrying`).
- Screen Model connection states: `idle`, `connecting`, `connected`, `disconnected`, `reconnecting`.

## Out Of Scope

- Permission request UI (events consumed; UI in a follow-on slice).
- Abort button UX.
- Diff viewer UI.
- Background delivery, push, or replacing SSE with broad polling. The chat
  timeline keeps a narrow changed-message watchdog poll as a temporary safety
  net.
- Connection Profile UI.

## Server APIs Involved

- `GET /global/event` — SSE (`{ payload: { type, properties } }` envelopes).
- `GET /session/status` — baseline and reconnect status refresh.
- Existing `GET /session/:id/message` for post-event message reload.

## Domain Objects

- `OpencodeEvent`, `EventStreamConnectionState`, `ForegroundEventStream`.
- Reuses `SessionStatus`, `SessionSummary`, `MessageRepository`, `SessionRepository`.

## UI States

- Stream connection indicator is internal to Screen Models; no dedicated probe screen.
- Session rows and chat header may show optional status subtitle when session is busy or retrying.

## Error States

- Stream transport failure maps to `disconnected` / `reconnecting`; manual home refresh still works.

## Permission Behavior

Not applicable in this slice.

## Design-System Components

- Reuse `OpenCodeListItem` subtitle for session status on home.
- Reuse `OpenCodeTopBar` / existing chat chrome for Session Chat status line.

## Navigation Entry Points

- Android: app-level lifecycle in `MainActivity`; `MainScreenViewModel`, `SessionChatViewModel`.
- iOS: `scenePhase` in `OpenCode_MobileApp`; `ProjectListHomeViewModel`, `SessionChatViewModel`.
