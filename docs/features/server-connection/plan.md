# Server Connection

## User Goal

Connect the mobile app to an opencode server on first launch, verify the
connection before saving, and return to edit server details when needed.

## In Scope

- One active **Connection Profile** per app install (base URL, username, password).
- **Connection onboarding** when no profile is saved: centered message plus
  **Connect to server** action on home.
- **Connection setup** full-screen: editable URL, username, and password with
  dev-friendly defaults; **Test connection** via `GET /global/health`; **Save**
  enabled only after a successful test.
- Password stored in iOS Keychain and Android Keystore-backed storage; URL and
  username in ordinary preferences.
- After save, land on project list home with normal probe and load.
- **Edit connection** from probe-failure UI and Settings; same full-screen
  setup pre-filled from the active profile.
- Dynamic runtime `ServerConnection` from the saved profile (no hardcoded
  `AppConfig` URL at runtime).

## Out Of Scope

- Multiple saved profiles or server switcher.
- Disconnect / delete profile flow.
- Standalone Sync Probe screen.
- Android emulator auto-rewrite of loopback URLs in saved profiles.

## Server APIs

- `GET /global/health` — test connection and home probe gating.

## Domain Objects

- `ConnectionProfile` — base URL, username, password.
- `ConnectionProfileStore` — active profile persistence and added worktrees.
- `SecureCredentialStore` — password read/write at persistence boundary.
- `ServerConnection` — runtime HTTP client configuration from active profile.
- `ProbeStatus` — `reachable`, `authFailed`, `unreachable` (Sync Probe language).

## UI States

**Root gate:** onboarding (no profile) or project home (profile exists).

**Connection setup screen:** `idle`, `testing`, `testSucceeded`, `testFailed`,
`saving`.

## User Actions

- `connectToServerTapped` — open setup screen from onboarding.
- `testConnectionTapped` — probe draft credentials.
- `saveTapped` — persist profile after successful test.
- `editConnectionTapped` — open setup screen from home or Settings.
- `retryProbeTapped` — re-run home probe (existing).

## Error States

- Auth failure (401/403).
- Unreachable / timeout.
- Unhealthy server response.
- Save failure (rare persistence errors).

## Design-System Components

- Reuse `OpenCodeTopBar`, `OpenCodeButton`, and `OpenCodeServerAddressInput` for
  server host entry; reuse `OpenCodeTextInput` for username and password.
- Do not use `OpenCodeBottomSheet` for connection setup itself (temporary
  full-screen presentation). Scheme picking inside the address input may use
  `OpenCodeBottomSheet`.

## Platform Entry Points

- iOS: root gate in `AppRootView`; full-screen setup from onboarding, probe
  failure, and Settings.
- Android: root gate in `MainNavigation`; full-screen setup route from
  onboarding, probe failure, and Settings.
