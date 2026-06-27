# Server Connection

## User Goal

Connect the mobile app to an opencode server on first launch, verify the
connection before saving, and return to edit server details when needed.

## In Scope

- One active **Connection Profile** per app install (base URL, username, password).
- **Connection onboarding** when no profile is saved: message plus **Connect to
  server** action.
- **Connection setup** bottom sheet: editable URL, username, and password with
  dev-friendly defaults; **Test connection** via `GET /global/health`; **Save**
  enabled only after a successful test.
- Password stored in iOS Keychain and Android Keystore-backed storage; URL and
  username in ordinary preferences.
- After save, land on project list home with normal probe and load.
- **Edit connection** from probe-failure UI and top-bar menu; same bottom sheet
  pre-filled from the active profile.
- Dynamic runtime `ServerConnection` from the saved profile (no hardcoded
  `AppConfig` URL at runtime).

## Out Of Scope

- Multiple saved profiles or server switcher.
- Disconnect / delete profile flow.
- Standalone Sync Probe screen.
- New design-system text-field component.
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

**Connection setup sheet:** `idle`, `testing`, `testSucceeded`, `testFailed`,
`saving`.

## User Actions

- `connectToServerTapped` — open setup sheet from onboarding.
- `testConnectionTapped` — probe draft credentials.
- `saveTapped` — persist profile after successful test.
- `editConnectionTapped` — open setup sheet from home.
- `retryProbeTapped` — re-run home probe (existing).

## Error States

- Auth failure (401/403).
- Unreachable / timeout.
- Unhealthy server response.
- Save failure (rare persistence errors).

## Design-System Components

- Reuse `OpenCodeBottomSheet`, `OpenCodeButton`, `OpenCodeTopBar`.
- Feature-local styled text fields (no reusable input component yet).

## Platform Entry Points

- iOS: root gate in `AppRootView`; setup sheet from onboarding and home.
- Android: root gate in `MainNavigation`; setup sheet from onboarding and home.
