# Bottom Sheet

## Intent

Provide a reusable modal action sheet for compact contextual choices that should
stay near the user's current screen. Use it for short action menus that need a
clear title, an explicit close affordance, and one or more tappable entries.

## Public API

- Variants: default modal sheet.
- States:
  - sheet: presented, dismissed.
  - entries: enabled, disabled.
- Android-only option: `keyboardAware` for multi-field form content inside the
  sheet (see Behavior).
- Required content: sheet `title`, close action, and entry `title`.
- Optional content:
  - sheet leading header action: an icon button before the title for in-sheet
    navigation such as returning to a parent level.
  - sheet pinned content: fixed content rendered between the header and the
    scrollable body, such as a filter field.
  - entry start decorator: `none` or `icon`.
  - entry end decorator: `none` or `icon`.

Entries are title-only in this version. Use `OpenCodeListItem` when a row needs
subtitle content, selected state, or a larger list treatment.

## Token Usage

- Semantic tokens: `color.background.elevated`, `color.surface.primary`,
  `color.surface.secondary`, `color.text.primary`, `color.text.disabled`,
  `color.border.subtle`, `color.icon.default`, `color.icon.strong`.
- Component tokens: `bottomSheet.cornerRadiusTop`, `bottomSheet.header.padding`,
  `bottomSheet.entry.minHeight`, `bottomSheet.entry.paddingHorizontal`,
  `bottomSheet.entry.paddingVertical`, `bottomSheet.entry.decorator.size`, and
  `bottomSheet.entry.decorator.iconSize`.
- Typography roles:
  - `type.heading` for the sheet title.
  - `type.body` for entry titles.

## Behavior

The component owns modal presentation and dismissal. The close button dismisses
the sheet, and outside-tap, drag, back, and accessibility dismissal behavior
should remain available.

When provided, the leading header action appears before the title and performs
caller-owned in-sheet navigation. It does not dismiss the sheet unless the
caller action does so. Use a clear accessibility label such as
`Back to providers` for icon-only navigation.

On Android, presentation and dismissal use the component motion tokens: 320 ms
for entering, 280 ms for exiting, and a scrim alpha of 0.32. The close button,
scrim tap, and back press call `onDismissRequest`; parent-driven `visible =
false` changes run the same exit animation after the caller has already cleared
presentation state.

Entries trigger their action only when enabled. Disabled entries reduce text and
icon emphasis and block interaction. Long entry titles truncate to one line.

On Android, pass `keyboardAware = true` when the sheet hosts text fields rather
than action entries. That mode applies `imePadding()` inside the sheet content
area so the keyboard lifts form content without shifting the full sheet by the
IME height.

On Android, the component owns vertical scrolling for long content. Feature
content inside the sheet body should not add its own vertical scroll container.
Use pinned content for fixed controls above the scrollable body when needed.

Icon decorators use catalog icons (`OpenCodeIcon` on iOS, `ImageVector` from
`OpenCodeIcons` on Android). Icons are decorative when the
entry title already describes the action; otherwise callers should provide an
accessibility label or description using the platform convention.

## Platform Notes

- iOS: presented with SwiftUI `.sheet` and native dismissal. Short entry lists use
  a measured compact height detent. Longer lists switch to a fixed 55% height
  detent with an internal `ScrollView`. Scrollable sheets disable interactive
  swipe-to-dismiss so list scrolling does not close the sheet; use the close
  button instead. Short compact sheets keep the default swipe-to-dismiss behavior.
- Android: presented with a full-window Compose popup so the component can use
  its own bottom-sheet motion timing while preserving close, scrim tap, back
  press, and downward drag dismissal. Short entry lists wrap to content height.
  Longer lists cap at 55% of screen height and scroll inside the sheet body.
  Drag-to-dismiss is limited to the header so list scrolling is not blocked.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered through platform interaction behavior.
- Disabled: included.
- Loading: not applicable for this component version.
- Error: not applicable for this component version.
- Decorators: start icon, end icon, and both icon positions are included.
- Header leading action: included.

## Parity And Divergence

Shared semantics for title, close behavior, entry states, and decorator roles are
aligned across iOS and Android. Platform-specific icon identifier inputs are
documented in `../divergences.md`.
