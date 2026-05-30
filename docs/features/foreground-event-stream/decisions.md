# Foreground Event Stream — Decisions

## Endpoint

Use `GET /global/event` for a single app-wide stream. Per-instance `GET /event` is equivalent for one-server v1; global matches multi-project home without per-worktree streams.

## Event envelope

Server sends JSON SSE data as `{ "payload": { "type", "properties" } }`. Parser also accepts unwrapped `{ "type", "properties" }` for resilience.

## Refresh strategy

Live Stores own refresh policy while the **foreground event stream** is active:

- **MessageLiveStore:** debounced full `listMessages` reload (300 ms) on `message.updated`, `message.part.updated`, `message.part.removed`, `message.removed`, `session.status`, `session.idle`, `session.error` for the bound session; 10 s changed-message watchdog poll while the chat observes that session.
- **SessionLiveStore:** debounced `listSessions` reload (500 ms) on `session.created`, `session.updated`, `session.deleted`, and reconnect.
- **SessionStatusLiveStore:** debounced bulk `getAllSessionStatuses` (300 ms) on `session.status`, `session.idle`, and reconnect.
- **Reconnect:** Live Stores refresh on synthetic `stream.reconnected`; home does not run a broad section reload per SSE event. Explicit project additions can reload with `skipProbe = true`; saved profile changes run the normal probe-backed refresh path.
- **Quiet refresh:** SSE-driven Live Store reloads in Session Chat preserve in-flight send state (pending optimistic bubble and `isSending`). See [Message Repository decisions](../message-repository/decisions.md#post-send-ux).

## Status display

Map server status `type` to product copy: `busy` → **Working**, `retry` → **Retrying**, `idle` → hidden. Loaded from `GET /session/status` on section load and updated from `session.status` / `session.idle` SSE events.

## Lifecycle

- **App foreground:** Android `ProcessLifecycleOwner` start/stop; iOS `scenePhase` `.active` / `.background` on root `ContentView`.
- **Session Chat (iOS):** `startObserving()` on appear; `stopObserving()` on explicit back or after a short delayed disappear (avoids killing SSE when the home Screen Model refresh re-renders the navigation parent). Android relies on scoped `ViewModel` cancellation on navigate back.

## Heartbeats

`server.connected` and `server.heartbeat` update connection state only; no UI refresh.
