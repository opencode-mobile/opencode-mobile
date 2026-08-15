# Server Connection Progress

## Shared planning

- [x] Plan, decisions, parity gaps documented.
- [x] CONTEXT.md glossary updated.
- [x] OPE-94: temporary full-screen connection setup planned.

## Design-system

- [x] Reuse `OpenCodeButton`, `OpenCodeTopBar`, `OpenCodeTextInput`.
- [x] Connection setup no longer depends on `OpenCodeBottomSheet` form mode.
- [x] Shared `OpenCodeServerAddressInput` (http/https prefix + IP:port) on both
  platforms with paste parsing and gallery entries.
- [x] Connection setup uses server address input for host entry.
- [x] Shared `OpenCodeEmptyState` for connected-no-projects home guidance.

## iOS

- [x] `ConnectionProfile` domain model and extended store.
- [x] Keychain credential store.
- [x] Profile-driven `ServerConnection` and DI reload.
- [x] Connection setup Screen Model and full-screen UI (OPE-94).
- [x] Connection onboarding root gate (centered home empty state).
- [x] Connected-no-projects empty state (Add project guidance).
- [x] Home / Settings edit connection entry points.
- [x] Unit tests.

## Android

- [x] `ConnectionProfile` domain model and extended store.
- [x] Keystore-backed credential store.
- [x] Profile-driven `ServerConnection` and DI reload.
- [x] Connection setup ViewModel and full-screen route (OPE-94).
- [x] Connection onboarding root gate (centered home empty state).
- [x] Connected-no-projects empty state (Add project guidance).
- [x] Home / Settings edit connection entry points.
- [x] Unit tests.

## OPE-172 Faster protocol probe

- [x] Persist last-known protocol on the Connection Profile
- [x] Open SSE on protocol hint (cached / persisted / v1)
- [x] Bound dual-health (parallel GETs; 3s connect / 5s request)
- [x] One in-flight probe; Android lock not held across HTTP
- [x] No re-probe on every SSE retry after a miss
- [x] Android SSE requires 2xx before live
- [x] iOS 5s stop grace (parity with Android)

## Remaining

- None for OPE-94 after checks.
