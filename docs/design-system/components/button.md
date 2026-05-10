# Button

## Intent

Provide a reusable action control for primary tap targets in feature screens and
component-gallery controls, with one shared API that supports text-first and
icon-only button needs.

## Public API

- Variants:
  - `fullWidthText(title)` - stretches to available width.
  - `compactText(title)` - sizes to content width.
  - `iconSquare(icon)` - fixed **40x40** square action.
  - `iconCircle(icon)` - fixed **40x40** circular action.
- Contrast: `high` (default) or `low`.
- States: `enabled`, `disabled`.
- Required content:
  - text title for text variants;
  - native icon identifier for icon variants (Android `ImageVector`, iOS SF
    Symbol `systemName`).
- Optional content:
  - icon accessibility label for icon variants.

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

Disabled state stays muted, blocks interaction, and overrides contrast.

`fullWidthText` always fills its container width and keeps a minimum touch
height. `compactText` keeps the same visual language but sizes to content.
`iconSquare` and `iconCircle` enforce a 1:1 fixed frame with centered icon
content. `iconCircle` differs from `iconSquare` by geometry only; contrast
behavior is shared across all variants.

Pressed feedback is provided through native button interaction behavior with
shared token mapping (secondary surface emphasis on iOS; equivalent mapping on
Android).

## Platform Notes

- iOS: implemented as a `Button` with a custom style that maps variant geometry
  and press feedback.
- Android: implemented with `Surface` plus optional `Modifier.clickable`, with
  variant-specific layout and sizing.

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
