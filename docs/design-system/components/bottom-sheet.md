# Bottom Sheet

## Intent

Provide a reusable modal action sheet for compact contextual choices that should
stay near the user's current screen. Use it for short action menus that need a
clear title, an explicit close affordance, and one or more tappable entries.

## Public API

- Variants: `actionList` and `form` content modes.
- States:
  - sheet: presented, dismissed.
  - entries: enabled, disabled.
- Required content: sheet `title`, close action, and entry `title`.
- Optional content:
  - sheet leading header action: an icon button before the title for in-sheet
    navigation such as returning to a parent level.
  - sheet pinned content: fixed content rendered between the header and the
    scrollable body, such as a filter field.
  - entry start decorator: `none` or `icon`.
  - entry end decorator: `none` or `icon`.
  - entry `destructive` (default false): title (and matching icon tint) use the
    semantic error color, same product meaning as overflow menu destructive
    rows.
  - iOS-only `scrollTargetID` to scroll a specific entry into view inside a
    scrollable sheet body.

Entries are title-only in this version. Use `OpenCodeListItem` when a row needs
subtitle content, selected state, or a larger list treatment.

## Token Usage

- Semantic tokens: `color.background.elevated`, `color.surface.primary`,
  `color.surface.secondary`, `color.text.primary`, `color.text.disabled`,
  `color.border.subtle`, `color.icon.default`, `color.icon.strong`.
- Component tokens: `bottomSheet.inset`, `bottomSheet.cornerRadiusTop`,
  `bottomSheet.cornerRadius`, `bottomSheet.displayCornerRadiusFallback`,
  `bottomSheet.header.padding`,
  `bottomSheet.entry.minHeight`, `bottomSheet.entry.paddingHorizontal`,
  `bottomSheet.entry.paddingVertical`, `bottomSheet.entry.decorator.size`, and
  `bottomSheet.entry.decorator.iconSize`.
- Typography roles:
  - `type.heading` for the sheet title.
  - `type.body` for entry titles.

## Behavior

The component owns modal presentation and dismissal. The close button dismisses
the sheet, and outside-tap, drag, back, and accessibility dismissal behavior
should remain available. The overlay is accessibility-modal so VoiceOver and
TalkBack stay on the sheet and cannot activate the dimmed presenter.

The sheet is a concentric float: **8** logical px from the left, right, and
bottom of the display. Bottom corners use `bottomSheet.cornerRadius` so they
share a center with the device curve. Top corners use the smaller
`bottomSheet.cornerRadiusTop` so the header is not eaten by the device radius.
The home indicator or gesture handle sits on the sheet. Side and bottom
gutters show the dimmed presenting screen.

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
Enabled entries use a spring press scale (~0.97) with a soft raised highlight so
context actions feel clickable.

Use `form` mode when the sheet hosts text fields. While the sheet is presented,
the presenting screen ignores keyboard-driven safe-area resizing so background
layout stays stable even if the sheet switches content modes. Only the sheet
responds: its header remains fixed, its body scrolls, and its available bottom
edge follows the keyboard. This avoids remeasuring compact content against a
changing keyboard safe area.

`actionList` keeps content-measured compact sizing and switches to a fixed
fraction-height scroll sheet only when the content exceeds the compact
threshold. Once a presentation is scrollable, it stays at that fixed height for
the rest of the presentation so filtering or other body changes do not resize
the sheet. Callers may set `alwaysFixedHeight` to force the 50% scrollable
detent for the whole presentation (used by Add project). `form` always uses a
scrollable body because keyboard appearance can reduce the available height at
any time, and it locks to the first measured height (growing only if content
needs more room, never shrinking).

On Android, the component owns vertical scrolling for long content. Feature
content inside the sheet body should not add its own vertical scroll container.
Use pinned content for fixed controls above the scrollable body when needed.

Icon decorators use catalog icons (`OpenCodeIcon` on iOS, `ImageVector` from
`OpenCodeIcons` on Android). Icons are decorative when the
entry title already describes the action; otherwise callers should provide an
accessibility label or description using the platform convention.

## Platform Notes

- iOS: presented with a window overlay (same pattern as Dialog) so the card can
  inset from the display edge without system sheet chrome. The hosting view is
  accessibility-modal and is removed when the host leaves the tree. Form sheets
  measure their natural header and body height once, capped at 560pt, then keep
  that height stable. Keyboard overlap shrinks available height and lifts the
  card so the internal scroll region, not the presenting screen, follows the
  keyboard.
  Short entry lists start at content height so the first measure is intrinsic
  row height. Compact height is header + rows + tight bottom padding. Measured
  content height is never shrunk after the intrinsic size is known, and updates
  stop once a presentation has locked to the scrollable height. Longer lists
  switch to a fixed 50% of the available height with an internal `ScrollView`
  and do not fall back to compact when body content shrinks. Overlay tap, close,
  and accessibility dismiss stay available. The overlay hosting controller
  reports no safe-area regions so `bottomSheet.inset` is measured from the
  display edge, not from the home indicator. The home indicator sits on the
  card. The card shape matches Android: top corners are
  `bottomSheet.cornerRadiusTop`, bottom corners are the screen display radius
  minus inset (fallback 55 − 8).
- Android: presented as an activity-window overlay (same idea as the iOS
  overlay) so the card can sit 8dp from the display edge. The overlay hides
  sibling decor views from TalkBack while it is attached. Compose `Dialog` and
  `Popup` windows stay above the nav inset and turn the sheet into an island.
  Form mode applies `imePadding()` to the sheet boundary—not its measured
  content—so the sheet follows IME animation while its body scrolls. The app
  navigation host handles system bars and display cutouts explicitly rather than
  using `safeDrawing`, because `safeDrawing` includes the IME and would resize
  every route behind a focused form. The component can use its own bottom-sheet
  motion timing while preserving close, scrim tap, back press, and downward drag
  dismissal. The overlay card is inset by `bottomSheet.inset` on the left, right,
  and bottom. Bottom corners use
  `WindowMetrics.windowInsets.getRoundedCorner()` (API 31+) minus inset, or the
  same fallback as iOS. Top corners use `bottomSheet.cornerRadiusTop`. Gesture
  navigation keeps the card 8dp from the display edge so the handle sits
  on the card. Compact lists wrap to content and do not add a nav-bar spacer
  inside the card. Three-button navigation (`navigation_mode` != gestural) adds
  the nav-bar height under that 8dp gap so the buttons stay uncovered.
  Short entry lists wrap to content height.
  Longer action lists and long form sheets use a fixed 50% of screen height and
  scroll inside the sheet body. Short form sheets still wrap to content. Form
  sheets lock their height after the first measure so filter changes do not
  resize the sheet. Drag-to-dismiss is limited to the header so list scrolling
  is not blocked.

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

Shared semantics for title, close behavior, entry states, decorator roles, 8px
inset, and concentric corner radius are aligned across iOS and Android.
Platform-specific icon identifier inputs and how display corner radius is
resolved are documented in `../divergences.md`.
