# Progress: Clear Data

## Shared Planning

- [x] Plan written.
- [x] Decisions recorded.

## Design-System

- [x] Confirmation uses `OpenCodeDialog` (see
  [OPE-61](https://linear.app/opencode-mobile/issue/OPE-61/opencode-dialog)).

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
