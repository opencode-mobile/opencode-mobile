# Foreground Event Stream — Decisions

## Endpoint

One app-wide stream, protocol-selected: v1 `GET /global/event` **and**
instance `GET /event`; v2 `GET /api/event`. Keep all three paths. Do not
collapse to v2-only.

Both protocols must deliver relevant live events over SSE (session
lifecycle, message/part, status, permissions, questions — not just
`server.connected` / `server.heartbeat`). Official desktop uses
`/global/event` for v1; instance `/event` is the directory-scoped bus.
REST reconnect is catch-up after a drop, not a substitute for missing SSE.
Project v2 types into the existing `OpencodeEvent` vocabulary; do not fork
Screen Models by protocol. Live Session Chat shape is TUI v2 — see
[server-api-v2](../server-api-v2/decisions.md). Message merge policy is
[message-store](../message-store/decisions.md).

## Event envelope

Server sends JSON SSE data as `{ "payload": { "type", "properties" } }`. Parser also accepts unwrapped `{ "type", "properties" }` for resilience.

## Refresh strategy

Feature-specific subscribers own refresh policy while the **foreground event
stream** is active:

- **MessageSync:** [message-store](../message-store/decisions.md).
- **SessionLiveStore:** debounced `listSessions` reload (500 ms) on `session.created`, `session.updated`, `session.deleted`, and reconnect.
- **SessionStatusLiveStore:** debounced bulk `getAllSessionStatuses` (300 ms) on `session.status`, `session.idle`, and reconnect.
- **Connect/reconnect:** Both platforms forward the server's
  `server.connected` event. `MessageSync` schedules a short-debounced
  synchronization for it; synthetic `stream.reconnected` reloads immediately
  and cancels a pending connected-event reload when one exists. After a
  transport drop (`Idle` / `Disconnected` / `Reconnecting`), returning to
  `Connecting` (cold resume after `stop()` clears `wasConnected`) or
  `Connected` triggers the same reconnect fullReload when `server.connected`
  is missed (OPE-119 / OPE-165). TransportResumed, `server.connected`, and
  `stream.reconnected` share one catch-up per ConnectionEpoch so overlapping
  signals do not double-fetch. Home does not run a broad section
  reload per SSE event. Explicit project additions can reload with
  `skipProbe = true`; saved profile changes run the normal probe-backed
  refresh path.
- **Quiet refresh:** MessageSync/display-pipeline updates in Session Chat
  preserve in-flight send state (pending optimistic bubble and `isSending`).
  See [Message Repository decisions](../message-repository/decisions.md#post-send-ux).

## Profile switch drops the previous server's live observations

- Date: 2026-08-17
- Decision: switching Connection Profiles clears session/status/ready live
  stores and `MessageSync` observation, pops session-scoped routes back to
  home, and treats `NotFoundError` / "Session not found" as terminal for that
  observation (no overflow-recovery retry loop). `MessageSync.clearCache`
  invalidates in-flight `shareIn` / detached loops; dropping the map alone
  does not stop them.
- Reason: after a v2→v1 switch the new HTTP client kept fetching the previous
  server's session IDs (`/home/ivan…` on a Mac v1 host), 404ing every 5s.
  Home Remove from home looked stuck when those leftover rows and chats
  stayed on screen.

## Status display

Map server status `type` to product copy: `busy` → **Working**, `retry` → **Retrying**, `idle` → hidden. Loaded from `GET /session/status` on section load and updated from `session.status` / `session.idle` SSE events.

## Lifecycle

- **App foreground:** Android `ProcessLifecycleOwner` start/stop with a **5s
  stop grace** so photo/document pickers (system activities that briefly stop
  the process) do not tear down SSE; iOS `scenePhase` `.active` / `.background`
  on `AppRootView` with the same **5s stop grace**, while `.inactive` keeps
  the stream (pickers). Both
  platforms treat `start()` as idempotent while a stream job is active.
  Cancelled SSE tasks must not publish Idle/Disconnected over a newer
  generation's Connecting state. While a foreground retry is in flight, chrome
  stays **connecting** (Connecting/Reconnecting) rather than **offline**
  (Disconnected).
- **Session Chat (iOS):** `startObserving()` on appear; `stopObserving()` on explicit back or after a short delayed disappear (avoids killing SSE when the home Screen Model refresh re-renders the navigation parent). Android relies on scoped `ViewModel` cancellation on navigate back.

## Device path is a second signal

- Date: 2026-08-15
- Decision: keep `EventStreamConnectionState` as SSE occupancy. Add
  `DeviceNetworkPath` (`ready` / `unavailable`). Chrome and mutations compose
  `(SSE × path)`. Unavailable is **offline** even if SSE is still Connected.
  Radio-off cancels the current SSE read and parks until a path exists; it does
  not `stop()` the stream or re-probe. **Ready** means some IP path, including
  LAN and Tailscale. Captive / `requiresConnection` is unavailable. No roam
  debounce.
- Reason: radio-off leaves URLSession / OkHttp SSE reads hanging, so chrome
  stayed **live**. OPE-165 must not publish Disconnected while a foreground
  retry is in flight; radio-off is a different cause and belongs on the overlay.
- Revisit when: Wi-Fi roam flashes offline and needs a short debounce.

## Heartbeats

`server.heartbeat` updates connection state only. `server.connected` schedules
a synchronization reload; synthetic `stream.reconnected` reloads immediately.

## Idle reconnect

- Date: 2026-08-19
- Decision: cancel and retry the foreground SSE read after **45s** with no
  received lines. Every SSE line counts as activity: JSON `data:` payloads,
  `server.heartbeat`, keep-alive comments (`: …`), and `id` / `event` fields.
  Comments are still not published as events. Both platforms log
  `foreground SSE idle timeout; reconnecting`.
- Reason: a hung `/api/event` subscriber can stay open without EOF. Counting
  only complete JSON `data:` lines treated comment keep-alives as silence and
  cancelled a live socket every 45s. A socket read timeout is too aggressive
  (heartbeats can be sparse); an activity watchdog on any received line is not.
- Revisit when: heartbeat interval is published and a tighter bound is safe.

## Logging

Foreground event stream implementations log connection lifecycle, retry timing,
and parsed SSE event type/session id. Session Chat logs the SSE events it
observes for the bound session. Logs must not include prompt text, message
bodies, credentials, authorization headers, or full response bodies.

Quick local pulls:

- Android: `adb logcat -v threadtime | rg "OpencodeApi|OpencodeSse|SessionChat"`
- iOS simulator live: `xcrun simctl spawn booted log stream --style compact --predicate 'subsystem == "dev.opencode.mobile"'`
- iOS simulator recent: `xcrun simctl spawn booted log show --last 10m --style compact --predicate 'subsystem == "dev.opencode.mobile"'`
