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
- Required content: sheet `title`, close action, and entry `title`.
- Optional content:
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
the sheet, and platform-native outside-tap, drag, and accessibility dismissal
behavior should remain available.

Entries trigger their action only when enabled. Disabled entries reduce text and
icon emphasis and block interaction. Long entry titles truncate to one line.

Icon decorators use platform-native icon inputs. Icons are decorative when the
entry title already describes the action; otherwise callers should provide an
accessibility label or description using the platform convention.

## Platform Notes

- iOS: presented with SwiftUI `.sheet` and native dismissal. Sheet content is
  measured from the visible sheet root and applied as a single
  `presentationDetents` height (no `.large` detent). The sheet starts from an
  estimated content height, then updates to the measured height after layout.
  The container uses `fixedSize(horizontal: false, vertical: true)` so its own
  layout stays intrinsic instead of expanding to the proposed sheet height.
- Android: presented with Material 3 `ModalBottomSheet`.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered through platform interaction behavior.
- Disabled: included.
- Loading: not applicable for this component version.
- Error: not applicable for this component version.
- Decorators: start icon, end icon, and both icon positions are included.

## Parity And Divergence

Shared semantics for title, close behavior, entry states, and decorator roles are
aligned across iOS and Android. Platform-specific icon identifier inputs are
documented in `../divergences.md`.
