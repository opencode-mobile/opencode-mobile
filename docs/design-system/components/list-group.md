# List Group

## Intent

Group a finite collection of related List Item rows inside one bordered,
clipped surface. Use it when rows form one visual set rather than independent
cards.

## Public API

- Variants: one standard grouped-list treatment.
- States: default and empty. Pressed, focused, disabled, loading, error, and
  selected states are not applicable because rows and owning features expose
  those states.
- Required content: an ordered collection of row views.
- Optional content: none.

## Token Usage

- Semantic tokens: `surface-base`, `border-weak-base`, and
  `border-weaker-base`.
- Component tokens: `listGroup.cornerRadius` in `../tokens.md`.
- Typography roles: not applicable; row content owns typography.

## Behavior

The component draws one surface and outer border, clips row content to the
shared corner radius, and inserts a separator between adjacent rows. It does
not add row selection, padding, scrolling, or accessibility meaning. An empty
collection renders no surface.

## Platform Notes

- iOS: a SwiftUI `VStack` renders rows and dividers inside one clipped overlay.
- Android: a Compose `Column` renders rows and horizontal dividers inside one
  shaped surface.

## Review Surfaces

Both Component Galleries include a realistic three-row changed-files group in
light and dark appearances through the shared gallery theme control.

## Parity And Divergence

The visible surface, separators, clipping, and empty behavior are shared. No
intentional divergence is planned.

## Completion Ledger

- Public API and states: complete.
- Token and typography mapping: complete; typography is row-owned.
- iOS and Android implementations: complete.
- Gallery and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: production iOS and Android builds pass. Android
  instrumentation compilation is blocked by the pre-existing unrelated
  `MainScreenTest` signature mismatch, so the native UI check remains blocked.
