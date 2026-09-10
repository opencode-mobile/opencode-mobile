# Foreground Event Stream

## User Goal

While the app is open, Session Chat and home reflect server activity in near real time.
SSE supplies live deltas. REST catch-up after a process gap is
[reactive-data-layer](../reactive-data-layer/plan.md), not a UI registry on
the stream.

## Now

`ForegroundEventStream` is the small caller seam (`start` / `stop`,
connection state, events). `DefaultForegroundEventStream` implements the
deeper `LiveConnection` authority: one atomic profile / protocol /
directory config identity, one generation per occupancy attempt, and a
monotonic gap revision. Process adapters own desired occupancy. Stores
reconcile by gap revision; they do not treat reconnect events as new
obligations.

## In Scope

- `ForegroundEventStream` wrapping v1 `GET /global/event` **and** every
  current instance `GET /event?directory=`, plus v2 `GET /api/event`. All
  stay. Both protocols must carry relevant live events (session lifecycle,
  message/part, status, permissions, questions), not heartbeats only.
- One required lease set per config: v1 is global plus every current
  added-project instance; v2 is `/api/event`. Connected / live requires
  every required lease. Each target has an independent 45s line watchdog
  and retry. Adding or removing directories changes only the affected
  leases and does not force a full reconnect or gap while incumbents stay
  live. Profile, protocol, or connection-identity change retargets and
  opens a gap.
- Foreground lifecycle: `ProcessWorldSignals` / `SceneWorldSignals` own
  desired occupancy and start or stop this stream. 5s background grace
  keeps the socket through pickers; grace itself is not a data gap.
  `AppRoot` is not a second stream owner.
- `stream.reconnected` / `server.connected` wake current-gap evaluation in
  stores. They are not new REST obligations. Store catch-up is
  [reactive-data-layer](../reactive-data-layer/plan.md).
- Session Chat incrementally merges relevant message events for the bound
  session and uses selective authoritative reloads for reconciliation.
- Home reloads session sections on session lifecycle events.
- Session status labels on home rows and Session Chat chrome (`Working`, `Retrying`).
- Screen Model connection states: `idle`, `connecting`, `connected`, `disconnected`, `reconnecting`.
- Device network path (`ready` / `unavailable`) composed with SSE occupancy and
  world `StreamOccupancy` (`Active` / `Grace` / `Stopped`) for **live** /
  **connecting** / **offline** chrome. Mutations use path-ready `canMutate`,
  not SSE connected. See [decisions](decisions.md#chrome-follows-occupancy).

## Out Of Scope

- Permission request UI (events consumed; UI in a follow-on slice).
- Abort button UX.
- Diff viewer UI.
- Background delivery, push, or replacing SSE with broad polling. Message
  poll policy is [message-store](../message-store/decisions.md).
- Connection Profile UI.

## Server APIs Involved

- `GET /global/event` — v1 SSE (desktop default). Must carry relevant events.
- `GET /event?directory=` — v1 instance SSE (directory-scoped). Carries
  `session.created`, `message.*`, status, permissions, questions. Keep
  every current added-project lease; do not treat as optional.
- `GET /api/event` — v2 SSE (live Session Chat shape). Same relevant-event bar.
- REST history and gap catch-up endpoints stay on
  [message-store](../message-store/plan.md) and
  [reactive-data-layer](../reactive-data-layer/plan.md).

## Expected Logs

- The foreground stream records connection lifecycle, retry timing, response
  status, parsed event type/session ID, and per-target idle timeout.
- `MessageSync` records message synchronization reasons and counts separately.
- Logs exclude message text, credentials, authorization headers, and full
  response bodies.

## Domain Objects

- `OpencodeEvent`, `EventStreamConnectionState`, `ForegroundEventStream`,
  `LiveConnection`, `LiveConnectionConfig`, `LiveGapRevision`,
  `DeviceNetworkPath`, `WorldSignals`, `StreamOccupancy`, `LiveUpdates`,
  `MessageSync`, and `InMemoryMessageStore`.
- Reuses `SessionStatus`, `SessionSummary`, `MessageRepository`, `SessionRepository`.

## UI States

- Stream connection indicator is internal to Screen Models; no dedicated probe screen.
- Home and Session Chat top-bar chrome is `live` / `connecting` / `offline`
  from `LiveUpdates`: path, world occupancy, and SSE state. Idle while occupying
  is **connecting**. Send / create / abort use `canMutate` (path ready), not SSE
  `live`.

## Error States

- Stream transport failure maps to `disconnected` / `reconnecting`; manual home
  refresh still works. One silent v1 target is not kept live by heartbeats on
  another.
- Message synchronization keeps the last good snapshot when event-driven REST
  reconciliation fails; a failed idle reconciliation ends local streaming state
  so the watchdog can recover.

## Permission Behavior

Not applicable in this slice.

## Design-System Components

- Reuse `OpenCodeListItem` subtitle for session status on home.
- Reuse `OpenCodeTopBar` / existing chat chrome for Session Chat status line.

## Navigation Entry Points

- Android: `ProcessWorldSignals` from `ProcessLifecycleOwner` starts and
  stops the stream. Wired at app start.
- iOS: `SceneWorldSignals` from `scenePhase`. `AppRootView` feeds
  `Foreground` / `Background` and calls `onForeground()` on cold home
  enter so the first active phase is not missed. That call is occupancy
  signaling, not a second stream owner.

## Validation

Shared traces and expected snapshots live under `shared/sse-recovery/`.
Both platforms run the same oracle against that contract. Do not check
transient run logs into the repo.
