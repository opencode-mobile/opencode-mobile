# Design System Typography

Typography should align with opencode's product identity while remaining native
and readable on each mobile platform.

OpenCode web typography evidence is recorded in
`docs/design-system/opencode-web-theme.md`. Treat the web system font stack as
fallback and sizing evidence, not as a replacement for the product font intent
below.

## Font Intent

- Primary UI: IBM Plex Sans.
- Mono and code: IBM Plex Mono.
- Display or condensed roles: IBM Plex Sans Condensed, only when a role is
  explicitly defined.

Platform fallback fonts are acceptable until font assets are added. Keep the
same role names in code and documentation so shipping real fonts later is one
synchronized change across both apps.

## Role Contract

Define typography by semantic role, not by raw size and weight in public
component APIs.

Initial roles:

- `type.display`
- `type.title`
- `type.heading`
- `type.body`
- `type.bodySecondary`
- `type.caption`
- `type.code`
- `type.button`

The exact platform values may differ to preserve native readability. The role
meaning should not differ.

## Platform Mapping

- iOS maps roles to SwiftUI font styles and custom font registrations when
  assets are available.
- Android maps roles to Material 3 typography and custom font families when
  assets are available.

Document intentional platform differences in `docs/design-system/divergences.md`.

## Initial Native Role Mapping

The first app themes use platform fallback fonts while preserving the documented
role names. IBM Plex Sans, IBM Plex Mono, and IBM Plex Sans Condensed assets are
intentionally deferred so they can be added once across both platforms.

| Role | Android mapping | iOS mapping |
| --- | --- | --- |
| `type.display` | `displaySmall`, 28sp, medium | 28pt, medium |
| `type.title` | `titleLarge`, 20sp, medium | 20pt, medium |
| `type.heading` | `titleMedium`, 16sp, medium | 16pt, medium |
| `type.body` | `bodyLarge`, 16sp, regular | 16pt, regular |
| `type.bodySecondary` | `bodyMedium`, 14sp, regular | 14pt, regular |
| `type.caption` | `bodySmall`, 13sp, regular | 13pt, regular |
| `type.code` | `CodeTextStyle`, 14sp, monospace | 14pt, monospaced |
| `type.button` | `labelLarge`, 14sp, medium | 14pt, medium |

These sizes keep the web extraction's compact feel where it is readable on
mobile, while allowing native body text to remain slightly larger than the
desktop web UI.
