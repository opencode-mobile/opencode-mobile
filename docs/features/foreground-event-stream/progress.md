# Foreground Event Stream — Progress

## Shared planning

- [x] Feature docs and OpenAPI subset (`api/opencode-events.openapi.yaml`)
- [x] Ordered `message.part.delta`, connect/reconnect, and selective message
  reconciliation behavior aligned across platforms

## Design-system

- [x] Reuse list item subtitle and chat header for status (no new components)

## Android

- [x] Events API + domain adapter + `DefaultForegroundEventStream`
- [x] Hilt module + `ProcessLifecycleOwner` coordinator in `MainActivity`
- [x] `SessionStatusLiveStore` and `SessionLiveStore` use
  `WhileSubscribed(5000)`; message observation now uses repository-owned
  `MessageSync`
- [x] Home collects session Live Stores; Session Chat observes the message
  repository display pipeline
- [x] Ordered SSE fan-out with `server.connected` forwarding and synthetic
  reconnect signaling
- [x] Session status on list rows and chat
- [x] Unit tests (event adapter/parser, sync/store/pipeline, Live Stores, and
  ViewModels)

## iOS

- [x] Events API client + domain adapter + stream service
- [x] Factory registration + `scenePhase` lifecycle in `AppRootView`
- [x] Session/status Live Stores use `SharedLiveObservation`; message observation
  now uses repository-owned `MessageSync`
- [x] Home collects session Live Stores; Session Chat observes the message
  repository display pipeline
- [x] `server.connected` forwarding and synthetic reconnect signaling match
  Android
- [x] Session status on list rows and chat
- [x] Unit tests (event adapter, sync/store/pipeline, Live Stores, and ViewModels)

## Completed follow-on

- [x] Permission request UI slice implemented under
  [`permission-approval`](../permission-approval/progress.md).

## Remaining work

- [x] [OPE-165](https://linear.app/opencode-mobile/issue/OPE-165/slow-reconnect-after-resume-from-inactivity)
      Slow reconnect after resume from inactivity — see
      [ope-165-plan.md](ope-165-plan.md). Implemented: idempotent iOS `start()`,
      generation-guarded cancel handlers, no offline flash while SSE retries,
      MessageSync catch-up on Connecting with one coalesced reload per
      ConnectionEpoch (iOS + Android).
- [x] [OPE-172](https://linear.app/opencode-mobile/issue/OPE-172/faster-protocol-probe-on-resume)
      Faster protocol probe on resume — SSE opens on a protocol hint;
      dual-health is bounded and off the first byte. See
      [`../server-connection/ope-172-plan.md`](../server-connection/ope-172-plan.md).
