# Decisions: Clear Data

## Orchestration pattern

iOS uses a `@MainActor static func clearAllData()` on `Container` (AppContainer)
because the LiveStores are `@MainActor` and the DI container already resolves
all singletons. Android adds a `clearAllData()` method to
`ConnectionSessionCoordinator` with the additional stores injected through the
`@Inject` constructor, keeping the orchestration in a single existing service
class.

## Cache clearing strategy

iOS LiveStores hold a `@MainActor` dictionary of `SharedLiveObservation`.
Setting the cache to `[:]` deallocates the observations, cancelling their
upstream tasks naturally. Android LiveStores hold a `ConcurrentHashMap` of
`SharedFlow`; clearing it removes references, and `shareIn` + `WhileSubscribed`
handles upstream cancellation when subscribers drain.

## Confirmation copy

Simple "Clear all data? This cannot be undone." -- no detailed itemised list.
Keeps the button self-explanatory given its location in Developer Tools.

## Android theme import gap

Android `ThemeManager` does not persist imported themes (unlike iOS which uses
UserDefaults). The `clearAll()` method on Android merely resets to the bundled
theme. This is a pre-existing parity gap documented in
`docs/features/theme/parity-gaps.md`.
