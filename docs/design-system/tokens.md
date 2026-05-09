# Design System Tokens

This document is the canonical planning source for design-system tokens before
native app implementation exists.

OpenCode web theme evidence is recorded in
`docs/design-system/opencode-web-theme.md`. Use it as source material when
choosing primitive palette values and semantic token mappings.

The token system has four levels:

1. Primitive palette
2. Base semantic tokens
3. Component tokens
4. Platform mappings

## Primitive Palette

Primitive palette values are raw color values and other low-level design values.
They describe available ingredients, not product meaning.

Examples:

- `palette.neutral.0`
- `palette.neutral.950`
- `palette.accent.blue.500`
- `palette.red.600`

Primitive values should not be used directly by feature screens.

## Base Semantic Tokens

Base semantic tokens describe product meaning that should stay consistent across
iOS and Android.

Examples:

- `color.background.primary`
- `color.background.secondary`
- `color.text.primary`
- `color.text.secondary`
- `color.border.subtle`
- `color.status.error`
- `color.status.warning`
- `color.status.success`

Semantic tokens may map to different platform APIs, but the meaning should stay
the same.

## Native Semantic Token Values

This is the first native light/dark token set, derived from the OpenCode web
theme evidence. Feature screens and reusable components should reference these
semantic meanings through platform theme APIs instead of hard-coding raw values.


| Token                        | Light       | Dark      |
| ---------------------------- | ----------- | --------- |
| `color.background.primary`   | `#f8f8f8`   | `#121212` |
| `color.background.secondary` | `#f3f3f3`   | `#191919` |
| `color.background.elevated`  | `#fcfcfc`   | `#151515` |
| `color.surface.primary`      | `#ffffff`   | `#1C1C1C` |
| `color.surface.secondary`    | `#f3f3f3`   | `#282727` |
| `color.surface.inset`        | `#f0f0f0`   | `#202020` |
| `color.text.primary`         | `#171717`   | `#EDEDED` |
| `color.text.secondary`       | `#6f6f6f`   | `#A0A0A0` |
| `color.text.tertiary`        | `#8f8f8f`   | `#707070` |
| `color.text.disabled`        | `#c7c7c7`   | `#505050` |
| `color.text.interactive`     | `#034cff`   | `#c0d4fb` |
| `color.border.subtle`        | `#e5e5e5`   | `#282828` |
| `color.border.default`       | `#00000029` | `#4d4c4a` |
| `color.icon.default`         | `#8f8f8f`   | `#7E7E7E` |
| `color.icon.strong`          | `#171717`   | `#ede8e4` |
| `color.status.success`       | `#12c905`   | `#089b00` |
| `color.status.warning`       | `#fbdd46`   | `#c78b05` |
| `color.status.error`         | `#fc533a`   | `#cb321c` |
| `color.status.info`          | `#a753ae`   | `#cd73d5` |


Android maps these values into Material 3 `ColorScheme` roles in
`android/app/src/main/java/dev/opencode/mobile/theme/Theme.kt`. iOS exposes the
same meanings through SwiftUI colors in
`ios/OpenCode Mobile/OpenCode Mobile/OpenCodeTheme.swift`.

## Component Tokens

Component tokens define reusable component-specific decisions derived from base
semantic tokens.

Examples:

- `button.primary.background`
- `button.primary.foreground`
- `button.primary.disabled.background`
- `sessionCard.active.border`
- `permissionBanner.warning.background`

Use component tokens when a component needs stable internal styling across
states, variants, or platforms.

### List item container

The outer list-row surface (`OpenCodeListItem`) uses one shared corner radius on
iOS and Android.

| Token | Value |
| --- | --- |
| `listItem.cornerRadius` | 8 (logical px) |

### List item start decorator (letter badge)

The letter variant of `OpenCodeListItem` uses a fixed **24×24** badge with a
small corner radius (`listItem.startDecorator.letter.cornerRadius`, **4**)
derived from these pairs. Do not use these tokens outside the list-item letter
badge without revisiting the design-system docs.

| Token | Light | Dark |
| --- | --- | --- |
| `listItem.startDecorator.letter.cornerRadius` | 4 (logical px) | 4 (logical px) |
| `listItem.startDecorator.letter.accent.violet.fill` | `#4c3d5c` | `#3a2f47` |
| `listItem.startDecorator.letter.accent.violet.tint` | `#e2d5f5` | `#d8c8ee` |
| `listItem.startDecorator.letter.accent.teal.fill` | `#2d4a42` | `#243832` |
| `listItem.startDecorator.letter.accent.teal.tint` | `#b8f5e0` | `#a8edd8` |
| `listItem.startDecorator.letter.accent.amber.fill` | `#5c3d28` | `#4a3224` |
| `listItem.startDecorator.letter.accent.amber.tint` | `#ffb86c` | `#ffaa55` |
| `listItem.startDecorator.letter.accent.blue.fill` | `#2a3a55` | `#1e2d42` |
| `listItem.startDecorator.letter.accent.blue.tint` | `#a8d4ff` | `#9ec8ff` |
| `listItem.startDecorator.letter.accent.rose.fill` | `#5c2d3d` | `#4a2533` |
| `listItem.startDecorator.letter.accent.rose.tint` | `#ffb8d4` | `#ffa8cc` |

### Choice badge

Interactive pill used for gallery controls and similar compact selectors.
Geometry is shared across platforms; fills map to semantic surfaces as
documented in `docs/design-system/components/choice-badge.md`.

| Token | Value |
| --- | --- |
| `choiceBadge.cornerRadius` | 16 (logical px; capsule when height is smaller) |
| `choiceBadge.minHeight` | 32 (logical px; enforced via vertical padding) |
| `choiceBadge.paddingHorizontal` | 12 (logical px) |
| `choiceBadge.paddingVertical` | 8 (logical px) |

### Button

Reusable action component with three variants: full-width text, compact text,
and square icon.

| Token | Value |
| --- | --- |
| `button.cornerRadius` | 8 (logical px) |
| `button.minHeight.fullWidthText` | 48 (logical px) |
| `button.minHeight.compactText` | 40 (logical px) |
| `button.paddingHorizontal.text` | 14 (logical px) |
| `button.paddingVertical.text` | 10 (logical px) |
| `button.iconSquare.size` | 40 (logical px; width equals height) |
| `button.icon.size` | 18 (logical px) |

## Platform Mappings

Platform mappings translate semantic and component tokens into native APIs:

- iOS: SwiftUI colors, materials, shapes, and typography roles.
- Android: Material 3 color roles, typography, shapes, and Compose modifiers.

Mappings may differ when platform conventions require it. Record intentional
differences in `docs/design-system/divergences.md`.

## Public API Rules

- Public component APIs should use semantic variants and explicit states.
- Do not expose arbitrary hex colors, one-off gradients, raw radius values, or
ad-hoc typography overrides as public customization.
- When a new token changes component behavior or appearance, update both
platform samples and previews in the same change once app projects exist.