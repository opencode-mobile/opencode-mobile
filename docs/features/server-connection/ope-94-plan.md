# OPE-94 Connection Setup Full Screen

## Goal

Replace the inconsistent Connect to server bottom sheet with a temporary
full-screen setup flow on iOS and Android, while keeping the home disconnected
state as a centered message plus Connect to server button.

## Shared behavior

- No saved profile → home shows centered onboarding copy and **Connect to
  server**.
- Tapping **Connect to server**, **Edit connection**, or Settings → Server
  Connection opens a full-screen setup screen (not a bottom sheet).
- Setup screen keeps URL / username / password fields, **Test connection**, and
  **Save** (enabled only after a successful test).
- Back dismisses without saving; Save dismisses after persisting.
- Connected with no curated projects → centered empty state explaining how to
  use **Add project** (dock icon / selector tile). Dock or add-project tile
  remains available.

## Platforms

- Android: `ConnectionSetupHome` NavDisplay route with `OpenCodeTopBar` back.
- iOS: full-screen cover with the same top bar and form content.

## Design-system

Reuse existing `OpenCodeTopBar`, `OpenCodeButton`, and `OpenCodeTextInput`. Add
`OpenCodeServerAddressInput` for host entry and `OpenCodeEmptyState` for the
connected-no-projects home message.
