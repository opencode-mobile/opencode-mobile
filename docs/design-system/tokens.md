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
