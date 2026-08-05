# Snackbar

## Intent

Provide a short, auto-dismissing message overlay for transient Session Chat
feedback (and other screens later). Use it for one-shot success or error copy
that must not become permanent chrome above scrolling content.

Prefer dialog- or composer-inline error fields when the failure belongs to an
open form or decision UI (rename, fork, permission, question). Prefer the
timeline full-screen error empty state when messages cannot load at all.

## Public API

- Variants: `default` (neutral notice) and `error`.
- States:
  - presented, dismissed.
  - loading / disabled / selected / empty: not applicable.
- Required content: message text.
- Optional content: none in v1 (no action button).
- Host API: callers present one message at a time with a stable identity so the
  same text can reappear after a later failure; the host auto-dismisses and
  notifies the caller to clear presentation state.

## Token Usage

- Semantic tokens:
  - `default`: `surface-raised-base`, `text-strong`, `border-weaker-base`
  - `error`: `error` fill (or surface with `error` emphasis), on-error /
    inverse strong text for readable contrast
- Component tokens: see `snackbar.*` in `docs/design-system/tokens.md`.
- Typography roles: `type.bodySecondary` for the message.

## Behavior

- When presented, the snackbar appears near the bottom of the host surface
  (above the Session Chat composer when used there).
- Auto-dismiss after `snackbar.autoDismissDuration` (~4s).
- Users may dismiss early via the platform-natural gesture (Material snackbar
  swipe on Android; tap on iOS).
- No action button in v1.
- Accessibility: announce the message as a polite live region / accessibility
  notification when presented.

## Platform Notes

- Android: Material 3 `SnackbarHost` / `Snackbar` wrapped by
  `OpenCodeSnackbarHost`, with variant colors mapped to OpenCode theme tokens.
  Material's `Snackbar(snackbarData)` already applies a **12** logical-pixel
  outer inset; the host only adds the **4** logical-pixel delta so total screen
  margins match `snackbar.screenMargin*` (**16**), matching iOS. The snackbar
  fills the width inside those margins.
- iOS: SwiftUI overlay toast (`OpenCodeSnackbarHost`) with matching geometry,
  tokens, and auto-dismiss timer.

## Review Surfaces

- Light and dark previews in each platform component file.
- Component Gallery **Snackbar** entry with controls to fire `default` and
  `error` messages.

## Completion Ledger

- Public API and applicable states: complete.
- Token and typography mapping: complete.
- iOS and Android implementations: complete.
- Gallery entries and previews: complete.
- Parity and divergences: complete; no intentional divergence.
- Automated checks: compile-focused; Session Chat wiring is a separate feature
  change.

## Parity And Divergence

No intentional divergence.
