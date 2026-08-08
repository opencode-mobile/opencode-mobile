# Foreground Event Stream — Decisions

## Endpoint

Use `GET /global/event` for a single app-wide stream. Per-instance `GET /event` is equivalent for one-server v1; global matches multi-project home without per-worktree streams.

## Event envelope

Server sends JSON SSE data as `{ "payload": { "type", "properties" } }`. Parser also accepts unwrapped `{ "type", "properties" }` for resilience.

## Refresh strategy

Feature-specific subscribers own refresh policy while the **foreground event
stream** is active:

- **MessageSync:** incrementally merges `message.updated`,
  `message.part.updated`, `message.part.delta`, `message.part.removed`, and
  `message.removed` for the bound session. It performs authoritative reloads on
  reconnect, `session.idle`, and legacy part mutations whose REST row lacks a
  stable part ID, plus a changed-message watchdog poll while the chat observes
  the session (paused while text is actively streaming).
- **SessionLiveStore:** debounced `listSessions` reload (500 ms) on `session.created`, `session.updated`, `session.deleted`, and reconnect.
- **SessionStatusLiveStore:** debounced bulk `getAllSessionStatuses` (300 ms) on `session.status`, `session.idle`, and reconnect.
- **Connect/reconnect:** Both platforms forward the server's
  `server.connected` event. `MessageSync` schedules a short-debounced
  synchronization for it; synthetic `stream.reconnected` reloads immediately
  and cancels a pending connected-event reload when one exists. After a
  transport drop (`Idle` / `Disconnected` / `Reconnecting`), returning to
  `Connected` also triggers the same reconnect fullReload (cold resume after
  `stop()` clears `wasConnected` and may miss `server.connected`). Transport
  drop clears any latched streaming text so the poll watchdog is not blocked.
  Home does not run a broad section reload per SSE event. Explicit project
  additions can reload with `skipProbe = true`; saved profile changes run the
  normal probe-backed refresh path.
- **Quiet refresh:** MessageSync/display-pipeline updates in Session Chat
  preserve in-flight send state (pending optimistic bubble and `isSending`).
  See [Message Repository decisions](../message-repository/decisions.md#post-send-ux).

## Status display

Map server status `type` to product copy: `busy` → **Working**, `retry` → **Retrying**, `idle` → hidden. Loaded from `GET /session/status` on section load and updated from `session.status` / `session.idle` SSE events.

## Lifecycle

- **App foreground:** Android `ProcessLifecycleOwner` start/stop with a **5s
  stop grace** so photo/document pickers (system activities that briefly stop
  the process) do not tear down SSE; iOS `scenePhase` `.active` / `.background`
  on `AppRootView` (`.inactive` keeps the stream, which covers pickers).
- **Session Chat (iOS):** `startObserving()` on appear; `stopObserving()` on explicit back or after a short delayed disappear (avoids killing SSE when the home Screen Model refresh re-renders the navigation parent). Android relies on scoped `ViewModel` cancellation on navigate back.

## Heartbeats

`server.heartbeat` updates connection state only. `server.connected` schedules
a synchronization reload; synthetic `stream.reconnected` reloads immediately.

## Logging

Foreground event stream implementations log connection lifecycle, retry timing,
and parsed SSE event type/session id. Session Chat logs the SSE events it
observes for the bound session. Logs must not include prompt text, message
bodies, credentials, authorization headers, or full response bodies.

Quick local pulls:

- Android: `adb logcat -v threadtime | rg "OpencodeApi|OpencodeSse|SessionChat"`
- iOS simulator live: `xcrun simctl spawn booted log stream --style compact --predicate 'subsystem == "dev.opencode.mobile"'`
- iOS simulator recent: `xcrun simctl spawn booted log show --last 10m --style compact --predicate 'subsystem == "dev.opencode.mobile"'`
