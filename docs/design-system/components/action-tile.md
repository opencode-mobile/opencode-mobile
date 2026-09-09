# Action Tile

## Intent

Provide a compact labeled action for action palettes inside a bottom sheet.
Use it when a short catalog icon plus a one-word label is enough to choose an
action. Do not use it for title-only rows; those stay
`OpenCodeBottomSheetEntry`.

## Public API

- Variants: one layout (`icon` above `label`).
- States: `enabled`, `disabled`. Pressed is platform interaction, not a
  distinct public state. Focused, loading, error, empty, and selected are
  not applicable.
- Required content: catalog icon, visible `label`.
- Optional content: accessibility label (defaults to `label`).

## Token Usage

- Semantic tokens: `color.surface.secondary` (`surface-raised-base`),
  `color.text.primary`, `color.text.disabled`, `color.icon.strong`,
  `color.icon.default`.
- Component tokens: `actionTile.cornerRadius`, `actionTile.minHeight`,
  `actionTile.paddingTop`, `actionTile.paddingHorizontal`,
  `actionTile.paddingBottom`, `actionTile.iconSize`,
  `actionTile.contentGap`, `actionTile.grid.gap`,
  `actionTile.grid.paddingHorizontal`, `actionTile.grid.minColumnWidth`,
  `actionTile.grid.minColumns`, and `actionTile.grid.maxColumns`.
- Typography roles: `type.body` for the label.

## Behavior

The tile triggers its action only when enabled. Disabled tiles mute icon
and label and block interaction. The visible label may be shorter than the
accessibility label. Long labels truncate to one line.

Enabled tiles use a spring press scale (~0.97). Icons are decorative when
an accessibility label is provided.

Callers arrange tiles with `OpenCodeActionTileGrid`. Column count is
`minColumns`…`maxColumns` from the available width and
`actionTile.grid.minColumnWidth`. A phone-width sheet stays at two columns; a
regular `layout.sheetMaxWidth` sheet can open a third or fourth. An odd last
tile stays leading; empty cells are not tappable.

## Platform Notes

- iOS: SwiftUI `Button` with the same press scale as bottom-sheet entries.
  Size catalog icons with `OpenCodeIconImage(icon:size:)`, never `.font`.
- Android: Compose `Surface` plus the same press scale as
  `OpenCodeBottomSheetEntry`. Catalog icons are `OpenCodeIcons` ImageVectors.

## Review Surfaces

- iOS: Component Gallery **Action Tile** (`ActionTileGalleryView`), light and
  dark, enabled and disabled.
- Android: Component Gallery **Action Tile** (`ActionTileGalleryScreen`),
  light and dark, enabled and disabled.

## Parity And Divergence

None.
