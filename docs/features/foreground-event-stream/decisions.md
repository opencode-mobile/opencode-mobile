# Foreground Event Stream — Decisions

## Endpoint

Use `GET /global/event` for a single app-wide stream. Per-instance `GET /event` is equivalent for one-server v1; global matches multi-project home without per-worktree streams.

## Event envelope

Server sends JSON SSE data as `{ "payload": { "type", "properties" } }`. Parser also accepts unwrapped `{ "type", "properties" }` for resilience.

## Refresh strategy

- **Messages:** debounced full `listMessages` reload (300 ms) on `message.updated`, `message.part.updated`, `message.removed`, `session.status`, `session.idle`, `session.error` for the bound session.
- **Busy poll:** while session status is `busy`, Session Chat also reloads messages every 2 s until status is no longer busy.
- **Quiet refresh:** SSE-driven and busy-poll reloads in Session Chat preserve in-flight send state (pending optimistic bubble and `isSending`). See [Message Repository decisions](../message-repository/decisions.md#post-send-ux).
- **Home:** debounced section reload (500 ms) on `session.created`, `session.updated`, `session.deleted`.
- **Reconnect:** when connection returns from `reconnecting` to `connected`, home runs `runHomeLoad(skipProbe = true)` and open chat reloads messages + status.

## Status display

Map server status `type` to product copy: `busy` → **Working**, `retry` → **Retrying**, `idle` → hidden. Loaded from `GET /session/status` on section load and updated from `session.status` / `session.idle` SSE events.

## Lifecycle

- **App foreground:** Android `ProcessLifecycleOwner` start/stop; iOS `scenePhase` `.active` / `.background` on root `ContentView`.
- **Session Chat (iOS):** `startObserving()` on appear; `stopObserving()` on explicit back or after a short delayed disappear (avoids killing SSE when the home Screen Model refresh re-renders the navigation parent). Android relies on scoped `ViewModel` cancellation on navigate back.

## Heartbeats

`server.connected` and `server.heartbeat` update connection state only; no UI refresh.
