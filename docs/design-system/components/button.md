# Button

## Intent

Provide a reusable action control for primary tap targets in feature screens and
component-gallery controls, with one shared API that supports text-first and
icon-only button needs.

## Public API

- Variants:
  - `fullWidthText(title)` - stretches to available width.
  - `compactText(title)` - sizes to content width.
  - `iconSquare(icon)` - fixed square action.
  - `iconCircle(icon)` - fixed circular action.
  - `iconCircleText(text)` - fixed circular action with a short text glyph
    (e.g. `/`) instead of a catalog icon, for actions with no suitable icon.
- Size: `medium` (default) or `large`.
  - Text variants: `medium` min-height **40**; `large` min-height **48**.
  - Icon variants: `medium` frame **40×40**; `large` frame **48×48**.
- Contrast: `high` (default), `low`, or `destructive`.
- States: `enabled`, `disabled`.
- Required content:
  - text title for text variants;
  - catalog icon for icon variants (Android `ImageVector` via `OpenCodeIcons`, iOS
    `OpenCodeIcon`);
  - short text glyph for `iconCircleText`.
- Optional content:
  - icon accessibility label for icon variants and `iconCircleText`.
  - `onLongClick` / `longPress` — optional second action. When omitted,
    the button is tap-only. When set, a long-press fires only that
    action: it must not also invoke tap, and it must not swallow the
    next tap.

## Token Usage

- Semantic tokens: `color.surface.primary`, `color.surface.secondary`,
  `color.text.primary`, `color.text.secondary`, `color.text.disabled`,
  `color.border.subtle`, `color.border.default`, `color.icon.strong`.
- Component tokens: `button.cornerRadius`, `button.minHeight.*`,
  `button.padding.*`, `button.iconSquare.size`, and
  `button.iconCircle.size`.
- Typography roles:
  - `type.button` for text variants.
  - icon glyph uses platform-native icon sizing.

## Behavior

Buttons are stateless and trigger the provided action only when enabled.
Disabled buttons reduce visual emphasis and block interaction.

For the **enabled** state, default styling is **high contrast**: the button
surface inverts against page background (dark fill with light label in light
mode; light fill with dark label in dark mode).

Low contrast buttons are for secondary actions that should remain available
without competing with a primary action. They use `color.surface.secondary` for
the fill, primary or strong content color for text/icons, and
`color.border.subtle` for the border.

Destructive contrast is for irreversible or hard-to-undo confirms (often as a
dialog primary). It uses semantic error fill with contrasting label text so
Remove / Delete / Clear read as destructive without inventing a separate button
variant. Map: Android `MaterialTheme.colorScheme.error` /
`onError`; iOS `OpenCodeColors.error` fill with `surfaceBase` label.

Disabled state stays muted, blocks interaction, and overrides contrast.

`fullWidthText` always fills its container width and keeps a minimum touch
height. `compactText` keeps the same visual language but sizes to content.
`iconSquare` and `iconCircle` enforce a 1:1 fixed frame with centered icon
content; `size` scales that frame between **40×40** and **48×48**. `iconCircle`
differs from `iconSquare` by geometry only; contrast behavior is shared across
all variants. `iconCircleText` shares `iconCircle` geometry and contrast
behavior, swapping the centered catalog icon for a short text glyph
(button-role typography) for actions the icon catalog does not cover.

Pressed feedback stays subtle: iOS low-contrast uses `surface-raised-base-hover`
plus a light opacity/scale nudge (`0.92` / `0.98`); high and destructive keep
their fill tokens and use the same opacity/scale nudge. Do not flash pressed
fills to `text-base` — that reads as an invert and is too high-contrast.
Android relies on Material ripple on the shaped `Surface`.

## Platform Notes

- iOS: implemented as a `Button` with a custom style that maps variant geometry
  and press feedback.
- Android: implemented with Material 3 `Surface(onClick = ..., enabled = ...)`
  so interaction and ripple indication follow the component shape (rounded
  rectangle or circle for icon variants). Avoid layering `Modifier.clickable`
  outside `Surface`, which can produce a rectangular ripple that ignores shape.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered via native pressed interaction.
- Disabled: included.
- Loading: not applicable in this component version.
- Error: not applicable in this component version.

## Parity And Divergence

Shared semantics for variants, states, and geometry are aligned across iOS and
Android. Platform-native icon identifier types are intentionally preserved and
tracked in `docs/design-system/divergences.md`.
