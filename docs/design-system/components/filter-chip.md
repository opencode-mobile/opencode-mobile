# Filter Chip

## Intent

Provide a single-select filter chip with optional count badge for use inside
compact chrome such as a floating dock.

Use when a horizontal filter needs capsule chips with selected/unselected
chrome. Prefer `OpenCodeFilterTabs` for a standalone sticky filter row outside a
dock.

## Public API

- Variants: default capsule chip.
- States: default (unselected), selected. Pressed is in-bounds scale/opacity
  feedback (no out-of-bounds glass bloom). Disabled, loading, error, and empty
  are not applicable.
- Required content: `title`, `selected`, `action`.
- Optional content: `count` (omit badge when null).

## Token Usage

- Semantic tokens: `text.strong` / `text.weaker` labels; neutral count badge fill
  from primary/text at low opacity; selected fill uses primary at low opacity
  or raised surface fallback.
- Typography roles: `type.button` title; caption-scale count.

## Behavior

Stateless. Tapping calls `action`. Selected exposes accessibility selected
trait. Count badge is monospaced digits with min widths for 1/2/3+ digit counts.

## Platform Notes

- iOS: iOS 26+ selected uses non-interactive regular glass capsule; unselected
  uses clear glass. Below iOS 26 selected uses raised surface + weak border.
- Android: selected fill + border; unselected transparent fill + weaker border
  (Material stand-in for glass chips).

## Review Surfaces

Session List gallery (chips in dock). Filter Tabs gallery remains the review
surface for the standalone tab row.

## Parity And Divergence

Same conceptual API. Material chrome differs per Floating Dock divergence.
