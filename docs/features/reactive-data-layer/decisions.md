# Reactive data layer decisions

## World signals, interest, and gap revisions

- Date: 2026-08-20
- Decision: three facts, not a refresh event bus. `WorldSignals` publishes
  process edges. Interest is the observe subscription. Catch-up is a
  `GapAuthority` revision inside `MessageSync` and the Live Stores.
  Unacknowledged obligation plus interested plus reachable runs REST. SSE
  patches while the pipe is live. Foreground is not "refresh now."
  Background grace is not a data gap.
- Reason: PR 218 / `ForegroundRestore` already starts SSE, then REST for a
  UI registry, while `MessageSync` and `SessionLiveStore` also REST on
  `stream.reconnected`. That is three owners. The registry is
  `setOpenSession`, `setHomeVisible`, `missedSessionCatchUp`, and
  `pendingHomeCatchUp`. It exists only because catch-up sat outside the
  observe loop. Putting catch-up in the loop deletes the registry.
- Revisit when: a store has no observe subscription and still needs
  catch-up. Add observe first.

## Observe subscription is interest

- Date: 2026-08-20
- Decision: collecting `observeSessions` / `observeDisplayMessages` /
  `observeStatuses` / `observePermissions` / `observeQuestions` /
  `observeTodos` is interest. `WhileSubscribed(5000)` on Android and the
  same 5s timeout on iOS `SharedLiveObservation` are the interest
  lifetime. Screen Models do not also register with a restore object.
  Home may keep collecting under Session Chat. `markHomeHidden` is scroll
  pin, not interest. iOS delayed `stopObserving` is collect lifetime, not
  a refresh hook. `SessionTodoLiveStore.setLive` stays session-working,
  not visibility. `SessionReadyStore.setFocusedSession` stays Ready focus.
- Reason: a second registry is a shallow interface. Interest as the
  observe subscription is the depth that gives callers leverage: one
  collect. `missedSessionCatchUp` was "restore ran before bind." Initial
  subscribe already REST-loads. The bug cannot recur once catch-up lives
  in the running observation.
- Revisit when: home list REST while chat is open is a measured problem.
  Pause by cancelling the home collect, not by `setHomeVisible`.

## WorldSignals is the occupancy clock

- Date: 2026-08-20
- Decision: `WorldSignal` is `Foreground`, `Background`, `BackgroundStop`,
  `PathReady`, `PathLost`, `ProfileSwitched`. `ProcessWorldSignals` and
  `SceneWorldSignals` are the prod adapters. `FakeWorldSignals` is the
  test adapter. Stores subscribe. The world adapter starts and stops
  `ForegroundEventStream` and does not call feature REST. `Background`
  is 5s occupancy grace only. `BackgroundStop` after 5s stops SSE and
  opens a gap. Idle-timeout can emit `BackgroundStop` without
  `Background`, and catch-up must still run on the next `Foreground`.
  `.inactive` does not stop the stream. Device network path stays its own
  OS seam for chrome, `EventStreamConnectionState` composed with
  `DeviceNetworkPathStatus`, and for `waitUntilReady` inside
  `DefaultForegroundEventStream`. `WorldSignals` also consumes path edges
  so stores do not wait on SSE occupancy to learn they are stale.
  `AppRoot` forwards scene edges; it is not a second stream owner.
- Reason: one clock. Two adapters at each true-external seam, lifecycle
  and path, plus fakes, justify the seams. Opencode REST/SSE stay behind
  existing remotes.
- Revisit when: Wi-Fi roam flashes `PathLost` / `PathReady` and needs a
  debounce. Until then, no roam debounce, same as the 2026-08-15 path
  decision.

## Network path is a world signal

- Date: 2026-08-20
- Decision: `PathLost` invalidates `GapAuthority` and does not REST.
  `PathReady` is the same catch-up class as `Foreground`,
  `reason=path-ready`: wake current-gap evaluation. Do not add a
  network-specific `restore()` policy on Screen Models.
- Reason: radio-off already cancels the SSE read. Catch-up still sat on
  return-to-foreground only, so a path that recovered while staying
  foregrounded depended on lucky `stream.reconnected` delivery. Same gap,
  same unpaid revision.
- Revisit when: path-ready catch-up races an in-flight SSE reconnect
  enough to need a tighter claim than `GapAuthority` single-flight.

## Pull-to-refresh is mark-dirty

- Date: 2026-08-20
- Decision: pull-to-refresh calls store `invalidate` with
  `reason=pull-to-refresh`. Chat may keep `refreshDisplayMessages` as the
  await wrapper. It is the same REST path as `background-return`. Home
  pull-to-refresh must not `clearCache` and `runHomeLoad()` / re-probe.
- Reason: a second fetch policy is how ForegroundRestore grew. User
  request and process gap differ only in `reason=`.
- Revisit when: pull-to-refresh must also refresh a snapshot screen that
  is not a Live Store.

## Dissolve the ForegroundRestore UI registry

- Date: 2026-08-20
- Decision: delete `ForegroundRestore`, `DefaultForegroundRestore`,
  `ForegroundRestorePhase`, `ForegroundHomeCatchUp`,
  `ForegroundHomeCatchUpRegistry`, `FakeForegroundRestore`,
  `setOpenSession`, `setHomeVisible`, `missedSessionCatchUp`,
  `pendingHomeCatchUp`. Do not replace them with a typed restore phase on
  Screen Models. `ForegroundEventLifecycleCoordinator` folds into
  `ProcessWorldSignals`.
- Reason: those methods are the third owner. Home catch-up via
  `refreshSuspending()` re-probes and reloads chrome, which is
  `refreshEverything()` by another name.
- Revisit when: never, unless observe interest is abandoned.

## SSE transport vs REST catch-up

- Date: 2026-08-20
- Decision: `ForegroundEventStream` stays the caller seam.
  `DefaultForegroundEventStream` implements `LiveConnection`. Dual
  protocol and both v1 event paths stay required. Connected requires
  every required lease. 5s stop grace and per-target 45s idle watchdog
  stay. REST is history and gap authority, not a substitute for
  heartbeat-only SSE. Store catch-up after a process gap lives in this
  feature, not in the stream type. `GapAuthority` is the unpaid-revision
  clock. `stream.reconnected`, `server.connected`, and snapshot changes
  only wake evaluation of the current revision.
- Reason: folding catch-up into the stream would make
  `ForegroundEventStream` the coordinator this slice refuses. Leaving
  catch-up only on SSE events is what made OPE-197 add ForegroundRestore.
  `ConnectionEpoch` is not the catch-up claim.
- Revisit when: the server publishes a heartbeat interval that justifies a
  tighter idle bound. That is still a transport change.

## GapAuthority acknowledges only accepted REST

- Date: 2026-09-10
- Decision: reconcile iff the current obligation is newer than the last
  successfully acknowledged revision. Failed or discarded REST does not
  acknowledge. Successful accepted replace or equality confirmation
  does. A newer gap during REST remains pending. Local invalidate
  (pull-to-refresh, parse failure, incomplete idle transcript) bumps a
  local obligation on the same authority. `BackgroundStop` and
  `PathLost` invalidate; `Background` does not.
- Reason: acknowledging a failed fetch, or treating reconnect as a new
  obligation, either dropped a gap or double-fetched a live bus.
- Revisit when: a store needs more than one unpaid revision at a time.

## Profile isolation

- Date: 2026-09-10
- Decision: profile switch clears session, status, message, permission,
  question, and todo observations. `SessionReadyStore.bindProfile` parks
  Ready watches per Connection Profile instead of wiping them.
- Reason: leftover observations 404 on the previous server's ids; wiping
  Ready dropped idle catch-up on return.
- Revisit when: a later live surface observes across profiles.

## Foreground Event Stream and Message Store decisions

- Date: 2026-08-20
- Decision: [foreground-event-stream](../foreground-event-stream/decisions.md)
  is narrowed to transport, occupancy chrome, lease set, and dual-protocol
  SSE. Return-from-background REST and the UI registry in that file are
  replaced by this file. [message-store](../message-store/decisions.md)
  still owns incremental `message.*` merge, bounded staging, and
  revision-gated authority. It does not own transport or lifecycle
  reconnect clocks.
- Reason: two live contracts must not both assign catch-up.
- Revisit when: MessageSync is split; keep catch-up on the observation
  loop that already owns REST plus SSE merge.

## Rejected: coordinator or Screen Model event bus

- Date: 2026-08-20
- Decision: do not build a process object that lists open sessions and
  chooses REST. Do not put app-lifecycle events on Screen Models. Do not
  layer a new reactive runtime on Live Stores.
- Reason: ForegroundRestore is already that coordinator. Screen Model
  `onAppear` refresh would be a fourth owner and would fight
  `WhileSubscribed`.
- Revisit when: never for the bus. A coordinator stays wrong even if
  store catch-up is slow; fix the store.
