# Empty State

## Intent

`OpenCodeEmptyState` is the centered instructional empty for a screen region:
title, supporting message, and an optional icon. Use it when the primary
surface has nothing to list yet and the user needs a short explanation of what
to do next (for example, connected home with no curated projects).

## Public API

- Variants: none (single layout).
- States: default only. Pressed, focused, disabled, loading, error, and selected
  are not applicable; callers hide the control when content exists.
- Required content: non-empty `title` and `message`.
- Optional content: `icon` (same Add project affordance or another semantic
  icon). No primary button — actions stay on surrounding chrome (dock, tile).

## Token Usage

- Semantic tokens: `background` inherits from parent; `text-strong` for title;
  `text-base` / on-surface-variant for message; `icon-default` for optional icon.
- Component tokens: none.
- Typography roles: `type.heading` for title; `type.bodySecondary` / body medium
  for message.

## Behavior

- Vertically and horizontally centered in the available space the caller
  provides.
- Title and message are multiline-capable and center-aligned.
- Optional icon sits above the title with modest spacing.
- Not interactive by itself.

## Platform Notes

- iOS: SwiftUI `VStack` with `OpenCodeTypography` / `OpenCodeColors`.
- Android: Compose `Column` with Material 3 typography mapped to the same roles.

## Review Surfaces

- Android Component Gallery entry **Empty State**.
- iOS Component Gallery entry **Empty State**.
- Preview: title + message; title + message + Add project icon.

## Completion Ledger

- Public API and applicable states: complete.
- Token and typography mapping: complete.
- iOS and Android implementations: complete.
- Gallery entries and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: complete (compile / gallery registration).

## Parity And Divergence

None.
