# Server Connection Progress

## Shared planning

- [x] Plan, decisions, parity gaps documented.
- [x] CONTEXT.md glossary updated.

## Design-system

- [x] Reuse `OpenCodeBottomSheet`, `OpenCodeButton`, `OpenCodeTopBar`.
- [x] Extend `OpenCodeBottomSheet` with Android `keyboardAware` for form sheets.
- [x] Feature-local form fields (no new reusable input component).

## iOS

- [x] `ConnectionProfile` domain model and extended store.
- [x] Keychain credential store.
- [x] Profile-driven `ServerConnection` and DI reload.
- [x] Connection setup Screen Model and sheet UI.
- [x] Connection onboarding root gate.
- [x] Home edit connection entry points.
- [x] Unit tests.

## Android

- [x] `ConnectionProfile` domain model and extended store.
- [x] Keystore-backed credential store.
- [x] Profile-driven `ServerConnection` and DI reload.
- [x] Connection setup ViewModel and sheet UI.
- [x] Connection onboarding root gate.
- [x] Home edit connection entry points.
- [x] Unit tests.

## Manual test

- [ ] Local server connect and save.
- [ ] Auth failure in setup sheet.
- [ ] Edit from menu and probe failure.
- [ ] Android emulator with `10.0.2.2` URL.
