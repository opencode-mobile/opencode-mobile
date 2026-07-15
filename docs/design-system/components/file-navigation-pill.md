# File Navigation Pill

## Intent

Provide compact, persistent previous/next navigation through a finite filtered
file collection while keeping the selected file's change totals visible. Use
it as a bottom-centered overlay on read-only change-review surfaces.

## Public API

- Variants: one semantic `standard` variant.
- States: default, pressed, previous-disabled, next-disabled, and
  overall-disabled. Native focus treatment applies where platform-relevant.
- Required content: nonnegative addition and deletion totals, independent
  previous and next availability, an overall enabled state, and previous/next
  actions.
- Optional content: none.
- Loading, error, empty, and selected states are not applicable. The caller
  controls visibility and supplies the overall enabled state for transient
  conditions such as refresh.

The fixed visible summary is `+N −N`. Both values remain present when zero.
The summary is informational and is not an action. The capsule keeps the
documented fixed 200-point/dp width as totals change; it never expands to the
available page width. Directional buttons stay pinned to their respective
capsule edges and the summary stays centered.

## Token Usage

- Semantic tokens: `surface-raised-base`, `surface-raised-base-hover`,
  `border-weaker-base`, `icon-strong-base`, `icon-weak-base`, `diff-add`, and
  `diff-delete`.
- Component tokens: `fileNavigationPill.*` in `../tokens.md`.
- Typography role: `type.button` in `../typography.md`.

## Behavior

The left chevron invokes Previous file and the right chevron invokes Next file.
Both controls remain visible at the capsule edges. Directional availability
disables each action independently; the overall enabled state disables both
without changing the current totals. Disabled actions block dispatch and use the weak icon role.
Pressed actions use the raised hover surface through native interaction state.

The component validates nonnegative totals. It does not own collection
position, filtering, refresh, visibility, safe-area placement, viewport reset,
or bottom content inset; those remain feature responsibilities.

Accessibility exposes two independent buttons named `Previous file` and
`Next file`. The noninteractive summary is announced as, for example,
`12 additions, 3 deletions`, including zero-valued phrases.

## Platform Notes

- iOS: SwiftUI capsule containing two native `Button` controls and a static
  summary. Each direction has a 44-point hit target and the capsule enforces
  the shared fixed width.
- Android: Material 3 raised capsule containing two `IconButton` controls and
  a static summary. Each direction has a 44-dp hit target and the capsule
  enforces the shared fixed width.

## Review Surfaces

- Both Component Galleries provide interactive first, middle, last, and
  refresh-disabled states with mixed and zero totals.
- SwiftUI and Compose previews cover light and dark appearance, directional
  boundaries, mixed totals, zero totals, and overall disabled behavior.

## Parity And Divergence

The public API, visible summary, actions, state meaning, geometry, semantic
roles, and accessibility meaning are shared. No intentional platform
divergence is planned.

## Completion Ledger

- Public API and applicable states: complete for nonnegative totals,
  independent directional availability, overall enabled state, and native
  pressed behavior. Loading, error, empty, and selected states are not
  applicable because the feature owns visibility and snapshot state.
- Token and typography mappings: complete in canonical documentation.
- iOS and Android implementations: complete with equivalent totals, actions,
  boundary and overall disabling, semantic roles, and accessibility behavior.
- Gallery entries and native previews: complete across first, middle, last,
  zero-total, refresh-disabled, light, and dark states.
- Automated formatting, action, disabled, summary, accessibility, and feature
  integration checks: complete through focused unit, component, and Changes
  screen UI tests on both platforms.
- Parity and divergences: complete; no observable divergence is known.
