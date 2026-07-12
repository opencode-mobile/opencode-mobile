# Server Connection Progress

## Shared planning

- [x] Plan, decisions, parity gaps documented.
- [x] CONTEXT.md glossary updated.

## Design-system

- [x] Reuse `OpenCodeBottomSheet`, `OpenCodeButton`, `OpenCodeTopBar`.
- [x] Extend `OpenCodeBottomSheet` with cross-platform `form` mode: fixed
  presenting screen, keyboard-following sheet boundary, fixed header, and
  internally scrolling form body.
- [x] Android navigation root excludes IME from global insets; system bars and
  display cutouts remain protected while keyboard handling stays screen-local.
- [x] Shared `OpenCodeTextInput` component and gallery entry on both platforms.
- [x] Connection setup URL, username, and password fields migrated to it.

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
