# Foreground Event Stream — Decisions

## Endpoint

One app-wide occupancy, protocol-selected: v1 `GET /global/event` **and**
every current instance `GET /event?directory=`; v2 `GET /api/event`. Keep
all three paths. Do not collapse to v2-only.

Both protocols must deliver relevant live events over SSE (session
lifecycle, message/part, status, permissions, questions — not just
`server.connected` / `server.heartbeat`). Official desktop uses
`/global/event` for v1; instance `/event` is the directory-scoped bus.
REST reconnect is catch-up after a drop, not a substitute for missing SSE.
Project v2 types into the existing `OpencodeEvent` vocabulary; do not fork
Screen Models by protocol. Live Session Chat shape is TUI v2 — see
[server-api-v2](../server-api-v2/decisions.md). Message merge policy is
[message-store](../message-store/decisions.md).

## LiveConnection is the transport authority

- Date: 2026-09-10
- Decision: `ForegroundEventStream` stays the caller seam.
  `DefaultForegroundEventStream` implements `LiveConnection`: one atomic
  profile / protocol / directory config identity, one generation per
  occupancy attempt, required vs live targets, and a monotonic gap
  revision. Stores read that snapshot. They do not invent a second
  reconnect clock.
- Reason: treating any open target as Connected, or treating
  `stream.reconnected` as a new REST obligation, double-fetched and hid
  silent v1 instance leases.
- Revisit when: the server publishes a single multiplexed event path that
  replaces the v1 global-plus-instance lease set.

## Required leases, not any-target live

- Date: 2026-09-10
- Decision: Connected / live requires every required lease. v1 requires
  global plus every current added-project instance. v2 requires
  `/api/event`. Each target has its own line watchdog and retry.
  Adding or removing directories starts or cancels only the affected
  leases. Incumbents that stay live do not force a full reconnect or a
  new gap. Profile, protocol, or connection-identity change retargets
  the whole set and opens `ConfigChanged`. Cached instance directories
  are keyed by complete connection identity (profile ID, connection
  identity, protocol). A later start on profile B must not reopen
  profile A's directories.
- Reason: a heartbeat on `/global/event` must not keep chrome live while
  an instance `/event` is dead. Adding a project must not drop the
  existing bus. Reusing another profile's cached directories opened the
  previous server's instance leases against the new host.
- Revisit when: v1 instance leases are no longer required.

## Event envelope

Server sends JSON SSE data as `{ "payload": { "type", "properties" } }`. Parser also accepts unwrapped `{ "type", "properties" }` for resilience.

## Refresh strategy

Feature-specific subscribers own refresh policy while the **foreground event
stream** is active:

- **MessageSync:** [message-store](../message-store/decisions.md).
- **SessionLiveStore:** debounced `listSessions` reload (500 ms) on
  `session.created`, `session.updated`, `session.deleted`. Reconnect and
  `server.connected` only wake [GapAuthority](../reactive-data-layer/decisions.md)
  evaluation; they are not a new REST obligation.
- **SessionStatusLiveStore:** debounced bulk `getAllSessionStatuses`
  (300 ms) on `session.status` and `session.idle`. Same wake-only rule
  for reconnect / `server.connected`.
- **Connect/reconnect:** Both platforms forward the server's
  `server.connected` event once per generation. Synthetic
  `stream.reconnected` fires when every required lease is live after a
  gap or cold resume. Stores evaluate the current gap revision; they do
  not share a `ConnectionEpoch` catch-up claim. Home does not run a
  broad section reload per SSE event. Process-gap catch-up is
  [reactive-data-layer](../reactive-data-layer/decisions.md).
  Explicit project additions can reload with `skipProbe = true`; saved
  profile changes run the normal probe-backed refresh path.
- **Quiet refresh:** MessageSync/display-pipeline updates in Session Chat
  preserve in-flight send state (pending optimistic bubble and `isSending`).
  See [Message Repository decisions](../message-repository/decisions.md#post-send-ux).

## Profile switch drops the previous server's live observations

- Date: 2026-08-17
- Decision: switching Connection Profiles clears session/status live stores
  and `MessageSync` observation, pops session-scoped routes back to home, and
  treats `NotFoundError` / "Session not found" as terminal for that
  observation (no overflow-recovery retry loop). `MessageSync.clearCache`
  invalidates in-flight `shareIn` / detached loops; dropping the map alone
  does not stop them. Ready watches and pills are parked per Connection
  Profile (`bindProfile`) instead of wiped, so a session that finishes while
  another server is active still becomes Ready after switching back.
  Permission, question, and todo live stores clear on the same path.
- Reason: after a v2→v1 switch the new HTTP client kept fetching the previous
  server's session IDs (`/home/ivan…` on a Mac v1 host), 404ing every 5s.
  Home Remove from home looked stuck when those leftover rows and chats
  stayed on screen. Wiping Ready on the same path dropped watches, so idle
  catch-up could not fire on return.
- Revisit when: a later store is added that observes across profiles.

## Status display

Map server status `type` to product copy: `busy` → **Working**, `retry` → **Retrying**, `idle` → hidden. Loaded from `GET /session/status` on section load and updated from `session.status` / `session.idle` SSE events.

## Lifecycle

- **App foreground:** Android `ProcessLifecycleOwner` and iOS
  `scenePhase` publish world signals. `ProcessWorldSignals` /
  `SceneWorldSignals` own desired occupancy and start or stop this
  stream. See [reactive-data-layer](../reactive-data-layer/decisions.md).
  **5s stop grace** so pickers do not tear down SSE; `.inactive` keeps
  the stream. Grace itself is not a data gap. `start()` stays
  idempotent while a stream job is active and reconciles directory
  leases in place when identity is unchanged. Cancelled SSE tasks must
  not publish Idle/Disconnected over a newer generation's Connecting
  state. While a foreground retry is in flight, chrome stays
  **connecting**. Cold resume after `stop()` still arms
  `pendingResumeBarrier` so the next fully live set emits
  `stream.reconnected` even though `wasFullyConnected` was cleared for
  chrome. iOS `AppRootViewModel.enterHome()` calls
  `SceneWorldSignals.onForeground()` so the first active scene is not
  missed; AppRoot is not a second stream owner.
  Android and iOS start occupancy in different adapter order on
  purpose: Android may see `onForeground()` before async profile load
  and starts from `onConnectionAvailable()` once a connection exists;
  iOS loads the profile before `enterHome()` / `onForeground()`. That
  is platform adapter sequencing, not a shared-trace contract.
- **REST after a gap:** not this module. Stores reconcile by
  `GapAuthority` revision. Do not register open sessions or home
  visibility on the stream.
- **Session Chat (iOS):** `startObserving()` on appear; `stopObserving()` on
  explicit back or after a short delayed disappear (avoids dropping the
  observe subscription when the home Screen Model refresh re-renders the
  navigation parent). Android relies on scoped `ViewModel` cancellation on
  navigate back. That is collect lifetime, not a restore registry.

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

## Chrome follows occupancy

- Date: 2026-08-21
- Decision: `LiveUpdates` composes path, `StreamOccupancy` (`Active` /
  `Grace` / `Stopped` from world signals), and SSE state into the same three
  product labels. Occupying plus Idle/Disconnected is **connecting**, not
  **offline**. `BackgroundStop` is **offline**. Grace with a still-connected
  socket stays **live**. Mutations (`canMutate`) require a Connection Profile,
  a ready path, and occupancy that is not Stopped. They do not wait for SSE
  `live`. Screen Models collect `LiveUpdates`; they do not fold the matrix.
  Store gap revisions stay out of the label. Quiet catch-up is still quiet.
- Reason: after the reactive data layer, Idle while reconnecting was a false
  **offline**, and REST already worked while the label blocked send/create.
- Revisit when: cause copy (no network vs unreachable) is a product need.

## Heartbeats

`server.heartbeat` updates connection state only. `server.connected` and
synthetic `stream.reconnected` wake current-gap evaluation. They do not
create a new catch-up obligation.

## Idle reconnect

- Date: 2026-08-19
- Decision: cancel and retry **that target's** SSE read after **45s** with
  no received lines. Watchdogs are independent per lease. Every SSE line
  counts as activity: JSON `data:` payloads, `server.heartbeat`, keep-alive
  comments (`: …`), and `id` / `event` fields. Comments are still not
  published as events. Both platforms log
  `foreground SSE idle timeout` with the target key.
- Reason: a hung `/api/event` subscriber can stay open without EOF. Counting
  only complete JSON `data:` lines treated comment keep-alives as silence and
  cancelled a live socket every 45s. A global watchdog would cancel healthy
  leases because another target was silent.
- Revisit when: heartbeat interval is published and a tighter bound is safe.

## Gap revisions

- Date: 2026-09-10
- Decision: `BackgroundStop`, `PathLost`, incumbent lease loss or idle,
  parser loss, and configuration / profile generation change open a
  monotonic gap revision. Background grace does not. Directory growth
  that only adds leases does not.
- Reason: treating grace or any-target flap as a gap forced REST while
  the bus was still live.
- Revisit when: a new loss cause must become a data gap.

## Android HTTP clients never close under live SSE

- Date: 2026-09-11
- Decision: Stop the foreground stream before `HttpClient.rebuild()`.
  `AppRoot.enterHome` does not rebuild clients. Cold start binds clients,
  enters Home, then starts SSE. Socket abort and closed-engine errors on
  the OkHttp/Ktor copy path are reconnectable; they must not kill the
  process. iOS `URLSession` already fails those as request errors.
- Reason: Android Crashlytics on `0.1.0-merge.212` recorded FATAL
  `ClientEngineClosedException`, `RejectedExecutionException` on a
  terminated OkHttp dispatcher, and `SocketException` during chunked SSE
  reads. `enterHome` rebuilt after `onConnectionAvailable` started SSE.
- Revisit when: Ktor's OkHttp engine parents the response-copy job to the
  request instead of `GlobalScope`.

## Logging

Foreground event stream implementations log connection lifecycle, retry timing,
and parsed SSE event type/session id. Session Chat logs the SSE events it
observes for the bound session. Logs must not include prompt text, message
bodies, credentials, authorization headers, or full response bodies.

Quick local pulls:

- Android: `adb logcat -v threadtime | rg "OpencodeApi|OpencodeSse|SessionChat"`
- iOS simulator live: `xcrun simctl spawn booted log stream --style compact --predicate 'subsystem == "dev.opencode.mobile"'`
- iOS simulator recent: `xcrun simctl spawn booted log show --last 10m --style compact --predicate 'subsystem == "dev.opencode.mobile"'`
