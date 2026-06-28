# Progress: Clear Data

## Shared Planning

- [x] Plan written.
- [x] Decisions recorded.

## Design-System

- [x] No new components required.

## iOS

- [x] `ConnectionProfileStore.clearAll()`
- [x] `ActiveConnectionManager.disconnect()`
- [x] LiveStore protocols: `clearCache()`
- [x] Default LiveStore implementations: `clearCache()`
- [x] `ThemeManager.clearAll()`
- [x] `Container.clearAllData()` orchestrator (includes
  `SessionModelSelectionStore.clearAll()`)
- [x] `SettingsView` UI

## Android

- [x] `ConnectionProfileStore.clearAll()`
- [x] `ActiveConnectionManager.disconnect()`
- [x] LiveStore interfaces: `clearCache()`
- [x] Default LiveStore implementations: `clearCache()`
- [x] `ThemeManager.clearAll()`
- [x] `ConnectionSessionCoordinator.clearAllData()` orchestrator (includes
  `SessionModelSelectionStore.clearAll()`)
- [x] `SettingsViewModel` + `SettingsScreen` UI + `Navigation.kt` wiring

## Verification

- [x] iOS builds without errors (pre-existing deprecation warnings only).
- [x] Android builds without errors.
- [x] Android unit tests pass.

## Post-implementation UX change

After the initial implementation, the user requested that instead of showing a
full-screen `.onboarding` state, the home page (with top bar) should always be
visible and display a centered "Connect to your server" message in its content
area when there is no active connection.

This was implemented by:

- Removing the `.onboarding` case from `AppRootState` on both platforms.
- `AppRootViewModel.load()` / `loadInitialState()` always enters `.home` /
  `Home`, regardless of connection state.
- Adding a `.disconnected` case to `ProjectListHomeState` /
  `MainScreenUiState.Disconnected`.
- In `runHomeLoad()`, checking `activeConnectionManager.connection == nil` first
  and transitioning to the disconnected state early.
- Adding `onProfileCleared` / `profileCleared` handlers in
  `ProjectListHomeViewModel` / `MainScreenViewModel` so the disconnected state
  activates immediately after clear data (not just on refresh).
- `ConnectionOnboardingView` / `ConnectionOnboardingScreen` is embedded inside
  the home screen's content area (below the top bar) rather than replacing the
  entire screen.
