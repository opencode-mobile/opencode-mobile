# Foreground Event Stream — Progress

## Shared planning

- [x] Feature docs and OpenAPI subset (`api/opencode-events.openapi.yaml`)

## Design-system

- [x] Reuse list item subtitle and chat header for status (no new components)

## Android

- [x] Events API + domain adapter + `DefaultForegroundEventStream`
- [x] Hilt module + `ProcessLifecycleOwner` coordinator in `MainActivity`
- [x] **Live Stores:** `MessageLiveStore`, `SessionStatusLiveStore`, `SessionLiveStore` with `WhileSubscribed(5000)`
- [x] Home and Session Chat collect Live Stores for data refresh; home observes connection state for the live-updates banner
- [x] Session status on list rows and chat
- [x] Unit tests (stores + ViewModels)

## iOS

- [x] Events API client + domain adapter + stream service
- [x] Factory registration + `scenePhase` lifecycle in `AppRootView`
- [x] **Live Stores:** mirrored protocols + `SharedLiveObservation` subscriber lifecycle
- [x] Home and Session Chat collect Live Stores for data refresh; home observes connection state for the live-updates banner
- [x] Session status on list rows and chat
- [x] Unit tests

## Validation

- [x] Android `:app:testDebugUnitTest`
- [x] iOS `OpenCode MobileTests` on simulator
- [ ] Manual against `opencode serve` on `:4096`

## Remaining

- [x] Permission request UI slice implemented under
  [`permission-approval`](../permission-approval/progress.md); live-server QA remains there.
