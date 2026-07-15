# Hunk Navigation Pill

## Intent

Provide persistent hunk movement and whole-document context expansion for a
read-only unified diff. Use it as a bottom-centered overlay in a file viewer.

## Public API

- Variants: collapsed-context and expanded-context.
- States: default, pressed, previous-disabled, next-disabled, and
  context-disabled. Native focus treatment applies where platform-relevant.
- Required content: previous, next, and expandable-context availability;
  current context mode; three actions; and, on Android, an optional selected-
  hunk offset callback for an owning scroll container.
- Optional content: none.
- Loading, error, empty, and selected states are not applicable. The owning
  viewer controls visibility and unavailable-preview presentation.

## Token Usage

- Semantic tokens: `surface-raised-base`, `surface-raised-base-hover`,
  `border-weaker-base`, `icon-strong-base`, and `icon-weak-base`.
- Component tokens: `hunkNavigationPill.*` in `../tokens.md`.
- Typography roles: not applicable; the component is icon-only.

## Behavior

Three equal 44-point/dp targets invoke Previous hunk, Expand/Collapse context,
and Next hunk. Directional actions remain visible and disable independently at
the document boundaries. The center action changes icon and accessibility
label with context mode and disables when the loaded patch contains no hidden
context. Disabled actions block dispatch. Pressed actions use the raised hover
surface.

The component does not own hunk position, context data, visibility, scrolling,
safe-area placement, or bottom content inset.

Accessibility exposes three independent buttons named `Previous hunk`,
`Expand context` or `Collapse context`, and `Next hunk`.

## Platform Notes

- iOS: a SwiftUI capsule containing three native `Button` controls.
- Android: a Material 3 raised capsule containing three `IconButton` controls.

## Review Surfaces

Both Component Galleries provide interactive first, middle, last,
expanded-context, and no-expand states. Native previews cover light and dark
appearances.

## Parity And Divergence

The public API, geometry, state meaning, actions, semantic roles, and
accessibility meaning are shared. No intentional divergence is planned.

## Completion Ledger

- Public API and applicable states: complete.
- Token mapping: complete; typography is not applicable for icon-only content.
- iOS and Android implementations: complete.
- Gallery entries and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: production iOS and Android builds and focused state tests
  pass. The iOS screen UI test is enabled in the shared scheme. Android
  instrumentation compilation is blocked by the pre-existing unrelated
  `MainScreenTest` signature mismatch, so the Android native UI check remains
  blocked.
