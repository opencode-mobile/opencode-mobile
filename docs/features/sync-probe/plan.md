# Sync Probe

## User Goal

Quickly confirm that the mobile app can reach the configured opencode server and
that the connection is usable before attempting session work.

## In Scope

- Add a shared **Sync Probe** feature slice for iOS and Android.
- Run a probe using server health data before treating a connection as usable.
- Expose a clear probe result in product language (`reachable`, `auth failed`,
  `unreachable`).
- Support manual retry from the probe error state.
- Keep probe behavior aligned in iOS and Android Screen Models.

## Out Of Scope

- Connection Profile create/edit flow and secure credential storage UI.
- Background synchronization guarantees or push notifications.
- Automatic project switching.
- Session list/message feature behavior changes beyond probe gating.

## Server APIs Involved

- `GET /global/health` - probe server reachability and auth validity.
- `GET /project/current` - optional follow-up check when probe succeeds.

## Domain Objects

- `ConnectionProfile` (existing app-level server connection configuration).
- `ConnectionProbeResult` (domain projection of probe outcome).
- `ProbeStatus` (`idle`, `probing`, `reachable`, `authFailed`, `unreachable`).
- Screen Model state and explicit actions/events for retry.

## UI States

- Idle (no probe started yet).
- Probing in progress.
- Reachable and usable.
- Auth failure.
- Network/transport failure.

## User Actions And Events

- `onAppear` triggers initial probe.
- `retryProbeTapped` reruns the probe.
- Probe completion event updates Screen Model state.

## Error States

- Invalid credentials (401/403 mapped to auth failure).
- Server unreachable or timeout.
- Unexpected server response mapped to generic probe failure.

## Permission Behavior

- Not applicable in this slice.

## Design-System Components Needed

- Reuse existing loading and error presentation patterns on each platform.
- Reuse existing button patterns for Retry action.
- No new reusable design-system component planned for this slice.

## Platform Entry Points

- iOS: run probe from the session-first home Screen Model load path.
- Android: run probe from the session-first home ViewModel load path.
