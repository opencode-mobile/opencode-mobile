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