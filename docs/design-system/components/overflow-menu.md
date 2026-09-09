# Overflow Menu

## Intent

Provide a compact anchored popup menu for secondary page actions that should stay
near the top bar without crowding trailing icon slots. Use it when a screen needs
a `moreVert` overflow affordance with a short list of tappable actions.

Each entry shows a single-line label. Leading icons are optional. When **any**
entry in the menu has an icon, every row reserves the standard icon slot so
mixed and selection menus stay aligned (empty slot when an entry omits its
icon). When **no** entry has an icon, the icon column and its spacing are
omitted entirely so text-only menus sit flush to the entry padding. Prefer
`OpenCodeBottomSheet` when the action list needs a title, close affordance, or
many entries with scrolling.

## Public API

- Variants: default anchored popup menu.
- States:
  - menu: expanded, dismissed.
  - entries: enabled, disabled; optional destructive emphasis.
- Required content: entry `title` and `onClick` handler.
- Optional content:
  - leading icon. Omitting icons on every entry collapses the icon column.
    Omitting the icon on only some entries keeps the reserved slot so rows stay
    aligned with icon entries (for example selected/unselected check rows).
  - entry accessibility label when the title alone is insufficient.
  - Android-only entry `contentDescription` for icon-only row semantics when the
    title is not sufficient for the leading icon.
  - `destructive` (default false) — uses semantic error text/icon color when
    enabled so irreversible actions such as Delete read as destructive.
  - iOS presentation via `OpenCodeTopBarOverflowHost(isPresented:entries:topBar:)`
    wrapping the top bar; optional `OpenCodeOverflowMenuTrigger` for the
    `moreVert` affordance.
  - Android presentation via `OpenCodeTopBarOverflowHost` with
    `OpenCodeTopBarAction.Icon(MoreVert)` toggling `expanded`.

Entries are action rows only in this version. Toggles, dividers, and subtitles
are out of scope.

## Token Usage

- Semantic tokens: `surface-raised-base`, `border-weak-base`, `text-strong`,
  `text-weak`, `icon-strong-base`, `icon-base`, and `error` for destructive
  entry text and icons. Enabled icons use `icon-strong-base` so they read with
  the label; `icon-weak-base` is too faint next to `text-strong`. Disabled
  icons use `icon-base`.
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
label rather than visible label suffixes. Pure text menus (every `icon` null)
do not reserve that column.

## Platform Notes

- iOS: presented with a window-level overlay anchored below the measured top
  bar (`OpenCodeOverflowMenuWindowOverlay`). Panel chrome uses
  `surface-raised-base`, `border-weak-base`, and `overflowMenu.cornerRadius`.
- Android: presented with a styled window popup aligned to the top-trailing
  overflow-button anchor. Panel chrome uses the same raised surface and
  `border-weak-base` stroke.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered through platform interaction behavior.
- Disabled: included.
- Destructive: included.
- Selected and unselected aligned rows: included.
- Loading: not applicable for this component version.
- Error: not applicable for this component version.

Component Gallery includes one live top-bar preview with controls for entry
count, enabled state, destructive emphasis, and aligned selection rows.

## Parity And Divergence

Shared semantics for entry content, enabled state, and dismissal behavior are
aligned across iOS and Android. Popup presentation differences are documented in
`../divergences.md`.
