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

## Connection setup uses form-mode bottom sheet

- Date: 2026-07-12
- Decision: connection setup stays on shared `OpenCodeBottomSheet` in semantic
  `form` mode on both platforms. The presenting screen does not resize for the
  keyboard; the sheet boundary follows the keyboard and the form scrolls under
  a fixed header.
- Reason: content-height detents and IME padding inside measured content caused
  feedback loops, jumps, and obscured controls. Keyboard insets belong at the
  modal boundary, while overflow belongs in one component-owned scroll region.
- Revisit when: native platform sheet APIs provide the same stable keyboard
  contract without component-specific presentation policy.
- Supersedes: the Android-only `keyboardAware` bottom-sheet option from
  2026-05-27.
- Status: **Superseded** (2026-08-01) by [Connection setup is temporary full
  screen](#connection-setup-is-temporary-full-screen).

## Connection setup is temporary full screen

- Date: 2026-08-01
- Decision: present connection setup as a temporary full-screen flow on both
  platforms instead of a bottom sheet. Android uses a NavDisplay route; iOS uses
  a full-screen cover. Both keep `OpenCodeTopBar` back, the same form fields,
  and Test / Save behavior.
- Reason: sheet presentation was inconsistent across platforms and still had
  keyboard and layout bugs; a full screen is a simpler interim contract.
- Revisit when: a durable modal or settings-embedded connection editor is
  designed with stable keyboard behavior on both platforms.
- Supersedes: [Connection setup uses form-mode bottom sheet](#connection-setup-uses-form-mode-bottom-sheet).
