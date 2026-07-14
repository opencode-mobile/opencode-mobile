# Overflow Menu

## Intent

Provide a compact anchored popup menu for secondary page actions that should stay
near the top bar without crowding trailing icon slots. Use it when a screen needs
a `moreVert` overflow affordance with a short list of tappable actions.

Each entry reserves a leading icon slot and shows a single-line label. The slot
may contain an icon or remain empty for aligned selection rows. Prefer
`OpenCodeBottomSheet` when the action list needs a title, close affordance, or
many entries with scrolling.

## Public API

- Variants: default anchored popup menu.
- States:
  - menu: expanded, dismissed.
  - entries: enabled, disabled.
- Required content: entry `title` and `onClick` handler.
- Optional content:
  - leading icon; omitting it preserves the standard icon slot so mixed icon
    and no-icon rows stay aligned.
  - entry accessibility label when the title alone is insufficient.
  - iOS trigger via `OpenCodeOverflowMenu` label closure and `isPresented`
    binding.
  - Android trigger via `OpenCodeTopBarAction.Icon(MoreVert)` toggling `expanded`.

Entries are action rows only in this version. Toggles, dividers, subtitles, and
destructive styling are out of scope.

## Token Usage

- Semantic tokens: `color.background.elevated`, `color.text.primary`,
  `color.text.disabled`, `color.icon.default`.
- Component tokens: `overflowMenu.cornerRadius`, `overflowMenu.minWidth`,
  `overflowMenu.paddingVertical`, `overflowMenu.entry.minHeight`,
  `overflowMenu.entry.paddingHorizontal`, `overflowMenu.entry.iconSize`,
  `overflowMenu.entry.iconSpacing`.
- Typography roles:
  - `type.body` for entry labels.

## Behavior

The menu dismisses when the user taps outside, presses back (Android), or selects
an enabled entry. Disabled entries remain visible with reduced emphasis and block
interaction. Long entry titles truncate to one line.

On Android and iOS, wrap the top bar in `OpenCodeTopBarOverflowHost`. The host
measures the rendered top-bar height, opens a compact panel directly below it,
and right-aligns the panel to the trailing edge inset. Entry content is
left-aligned inside the panel. Tapping outside dismisses the menu.

Icon decorators use catalog icons (`OpenCodeIcon` on iOS, `ImageVector` from
`OpenCodeIcons` on Android). Icons are decorative when the entry title already
describes the action. Selection menus use the catalog check icon for the
selected row and an empty reserved slot for unselected rows; the selected state
remains available to assistive technology through the entry accessibility
label rather than visible label suffixes.

## Platform Notes

- iOS: presented with a styled popover anchored to the trigger. Panel chrome
  uses `surfaceBase`, `borderWeakerBase`, and `overflowMenu.cornerRadius`.
- Android: presented with a styled window popup aligned to the top-trailing
  overflow-button anchor. Panel chrome uses `surface`, `outlineVariant`, and
  `overflowMenu.cornerRadius`.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered through platform interaction behavior.
- Disabled: included.
- Selected and unselected aligned rows: included.
- Loading: not applicable for this component version.
- Error: not applicable for this component version.

Component Gallery includes one live top-bar preview with controls for entry
count, enabled state, and aligned selection rows.

## Parity And Divergence

Shared semantics for entry content, enabled state, and dismissal behavior are
aligned across iOS and Android. Popup presentation differences are documented in
`../divergences.md`.
