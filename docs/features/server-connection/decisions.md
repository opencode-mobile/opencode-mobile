# Server Connection Decisions

## Single active Connection Profile for v1

- Date: 2026-05-27
- Decision: persist exactly one active Connection Profile; no server list or
  switcher.
- Reason: matches v1 bounded context (one opencode server, one project context
  at a time).
- Revisit when: multi-server or multi-project switching is in scope.

## Test connection before save

- Date: 2026-05-27
- Decision: **Save** stays disabled until the latest **Test connection** succeeds
  via `GET /global/health`.
- Reason: avoid persisting credentials the user never verified.
- Revisit when: offline-first profile draft is needed.

## Secure password storage from first ship

- Date: 2026-05-27
- Decision: password in iOS Keychain and Android Keystore-backed storage; URL
  and username in ordinary preferences.
- Reason: architecture already requires secure credential storage; connection
  flow is the first user-facing credential UI.
- Revisit when: credential migration or export is added.

## Onboarding only when no saved profile

- Date: 2026-05-27
- Decision: full onboarding screen when no profile exists; probe failure on home
  shows Retry plus Edit connection, not onboarding.
- Reason: distinguish first-run setup from server-went-away recovery.

## Android emulator URL entered manually

- Date: 2026-05-27
- Decision: do not auto-rewrite `127.0.0.1` to `10.0.2.2` for saved runtime
  URLs; user enters `10.0.2.2` on Android emulator.
- Reason: explicit user choice during grilling; saved URL matches what user typed.
- Revisit when: connection UX adds platform-aware URL hints or rewrite at probe
  time only.
- Supersedes: partial reliance on `resolveDevServerBaseUrl` for runtime requests.

## Form defaults for local development

- Date: 2026-05-27
- Decision: prefill URL `http://127.0.0.1:4096/`, username `opencode`, empty
  password; all fields editable.
- Reason: matches prior hardcoded dev setup and reduces local testing friction.

## Supersedes hardcoded endpoint on home

- Date: 2026-05-27
- Decision: runtime server URL comes from saved Connection Profile, not
  `AppConfig`.
- Reason: connection flow replaces temporary hardcoded endpoint from project list
  home decisions.
- See: [Project List Home decisions](../project-list-home/decisions.md).

## Android connection setup uses keyboard-aware bottom sheet

- Date: 2026-05-27
- Decision: connection setup stays on shared `OpenCodeBottomSheet` with
  `keyboardAware = true`; do not use a separate Dialog wrapper for the form.
- Reason: nested scrolling inside `ModalBottomSheet` conflicted with IME resize;
  `skipPartiallyExpanded`, zero sheet window insets, and `imePadding()` on the
  sheet body keep the form usable with the on-screen keyboard.
- Revisit when: Material3 adds first-class form-sheet IME behavior or a reusable
  design-system text-field component changes layout constraints.
