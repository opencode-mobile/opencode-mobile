# Foreground Event Stream — Progress

## Shared planning

- [x] Feature docs and OpenAPI subset (`api/opencode-events.openapi.yaml`)

## Design-system

- [x] Reuse list item subtitle and chat header for status (no new components)

## Android

- [x] Events API + domain adapter + `DefaultForegroundEventStream`
- [x] Hilt module + `ProcessLifecycleOwner` coordinator in `MainActivity`
- [x] Home and Session Chat ViewModel integration
- [x] Session status on list rows and chat
- [x] Unit tests

## iOS

- [x] Events API client + domain adapter + stream service
- [x] Factory registration + `scenePhase` lifecycle in `ContentView`
- [x] Home and Session Chat Screen Model integration
- [x] Session status on list rows and chat
- [x] Unit tests

## Validation

- [x] Android `:app:testDebugUnitTest`
- [x] iOS `OpenCode MobileTests` on simulator
- [ ] Manual against `opencode serve` on `:4096`

## Remaining

- Permission request UI slice
- Abort UX slice
