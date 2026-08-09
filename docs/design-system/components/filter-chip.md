# Filter Chip

## Intent

Provide a single-select filter chip with optional count badge for use inside
compact chrome such as a floating dock. When the chip represents a removable
filter (for example a project filter on home), it may also expose a remove
control.

Use when a horizontal filter needs capsule chips with selected/unselected
chrome. Prefer `OpenCodeFilterTabs` for a standalone sticky filter row outside a
dock.

## Public API

- Variants: default capsule chip.
- States: default (unselected), selected. Pressed feedback: iOS uses subtle
  scale/opacity; Android uses ripple on the chip hit target (no scale/opacity
  animation). Disabled, loading, error, and empty are not applicable.
- Required content: `title`, `selected`, `action`.
- Optional content: `count` (omit badge when null); `removeAction` / `onRemove`
  (omit the remove circle when null). Session List Dock only supplies remove
  when the chip is the selected project filter.

## Token Usage

- Semantic tokens: `text.strong` for title and count label in both selected and
  unselected; count badge fill uses primary/text at fixed low opacity (`0.10`).
  Selected chip background uses primary at low opacity or raised surface /
  regular glass; unselected uses clear / transparent fill. Remove circle uses
  the same low-opacity fill as the count badge.
- Component tokens: `filterChip.metaSize`, `filterChip.remove.iconInset` in
  `../tokens.md`.
- Typography roles: `type.button` title; caption-scale count. Weight does not
  change with selection.

## Behavior

Stateless. Tapping the title/count region calls `action`. Tapping the remove
circle calls `removeAction` / `onRemove` and does not select the chip. Selected
exposes accessibility selected trait. Count badge is monospaced digits with min
widths for 1/2/3+ digit counts. Remove uses accessibility label
`Remove {title}`.

The remove glyph is inset inside a circular meta control (`metaSize` with
`remove.iconInset` padding) so it matches across platforms. On iOS, size the
catalog icon with frame/padding — not `.font` — per [`../icons.md`](../icons.md).

## Platform Notes

- iOS: iOS 26+ selected uses regular (non-interactive) glass capsule with fill;
  unselected uses clear glass. Press uses subtle scale/opacity — not
  `.glassEffect(...interactive())`, which is too loud on dense chip rows.
  Label and count colors stay the same. Below iOS 26 selected uses raised
  surface + weak border; unselected uses a light stroke so press feedback still
  reads. Remove control is a separate plain button beside the select hit target.
- Android: selected fill vs transparent unselected fill; label, count, and
  border treatment stay constant (Material stand-in for glass chips). Remove
  uses a nested clickable on the circle so it does not also fire chip `onClick`.

## Review Surfaces

Session List gallery (chips in dock). Filter Tabs gallery remains the review
surface for the standalone tab row. Session Composer / Choice Badge gallery
cover glass **Choice Badge** pickers (related chrome, separate component).
SwiftUI `#Preview("Filter Chip")` and Android `@Preview` on
`OpenCodeFilterChip` include a removable selected chip.

## Parity And Divergence

Same conceptual API. Material chrome differs per Floating Dock divergence.
Remove icon inset is shared (`filterChip.remove.iconInset`).
