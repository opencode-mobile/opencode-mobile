# Clear Data

## User Goal

As a developer, wipe all app state from the Developer Tools section to start
fresh without reinstalling.

## In Scope

- "Clear All Data" row in Developer Tools on both platforms.
- Confirmation dialog: "Clear all data? This cannot be undone." with Cancel /
  Clear buttons.
- Clears: connection profile (URL + username) -> password
  (Keychain/EncryptedSharedPreferences) -> worktrees -> imported themes ->
  in-memory Live Store and message synchronization caches.
- Disconnects ActiveConnectionManager (app shows "Connect to a server").
- Stays on Settings screen after clearing.
- Best-effort per-item clearing; failures are silently swallowed.

## Out Of Scope

- Clearing individual data categories separately.
- Surfacing the button outside Developer Tools.
- Persisting an undo log or backup.
- Animating the cleared state transition.

## Server APIs

None (all operations are local).

## Domain Objects

- `ConnectionProfileStore` -- new `clearAll()` method.
- `SessionModelSelectionStore` -- new `clearAll()` method (included in Clear All
  Data orchestration).
- `ActiveConnectionManager` -- new `disconnect()` method.
- `ConnectionSessionCoordinator` -- orchestrates the clearing sequence.
- `SessionLiveStore`, `SessionStatusLiveStore`, `PermissionLiveStore`, and
  `QuestionLiveStore` -- `clearCache()` methods.
- `MessageRepository.clearMessageCache()` -- clears the `MessageSync` store and
  shared observation cache.
- `ThemeManager` -- new `clearAll()` method re-loads the bundled theme.

## UI States

- Settings screen idle.
- Confirmation alert/dialog visible.
- After clear: same Settings screen; ActiveConnectionManager is nil so the app
  root gate shows the onboarding state on next render.

## User Actions

- `clearDataTapped` -- show confirmation.
- `clearDataConfirmed` -- execute the wipe.
- `clearDataCancelled` -- dismiss confirmation.

## Error States

- Clearing is best-effort (local-only ops). No error surfaced to user.

## Design-System Components

- Reuse `OpenCodeListItem` with a trash/deletion icon.
- Confirmation uses `OpenCodeDialog` (title, subtitle, Cancel/Clear).

## Platform Entry Points

- iOS: Settings -> Developer Tools -> "Clear All Data" row -> confirmation
  alert.
- Android: Settings -> Developer Tools -> "Clear All Data" row -> confirmation
  dialog.
