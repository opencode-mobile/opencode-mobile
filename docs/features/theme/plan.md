# Theme System

## User Goal

Users can apply OpenCode desktop themes on mobile: pick from bundled presets or
import custom OC-2 JSON, with consistent color appearance across light and dark
modes.

## In-Scope

- Load bundled OC-2 theme presets at launch on both platforms (default: OC-2)
- Resolve palette seeds + overrides into a full token dictionary at runtime
- Expose resolved tokens through platform-native color APIs
- Align all app-internal token names with the OpenCode desktop schema
- Settings theme picker for bundled desktop presets
- Import a custom OC-2 JSON via document picker (alongside bundled presets)
- Persist selected theme and imported themes in app-local storage
- Apply theme on selection (restart required today; see follow-up)
- Validate theme JSON structure on import
- Regenerate bundled presets from opencode desktop via `npm run themes:sync`

## Out-Of-Scope

- Theme editor or visual builder
- Theme sharing / export
- Server-synced themes
- Dynamic theme switching without app restart (deferred to follow-up)
- Live token preview / swatches in the theme picker (deferred to follow-up)

## Server APIs Involved

None. Themes are loaded from bundled or imported JSON only.

## Domain Objects

- `OpenCodeTheme` — top-level theme with metadata and mode variants
- `ThemeVariant` — one light or dark variant containing palette + overrides
- `PaletteSeeds` — the 7 required + 3 optional seed colors
- `ResolvedTokens` — flat dictionary of resolved token name → hex string
- `ThemePreset` — lightweight `{ id, name }` entry from bundled manifest
- `ThemeManager` — singleton managing current theme, persistence, and resolution

## UI States

- **Loading**: app launch while theme JSON is loaded and resolved
- **Default**: OC-2 preset applied
- **Preset active**: user selected a bundled desktop preset
- **Custom theme active**: imported theme applied
- **Picker open**: bottom sheet listing bundled presets plus imported themes
- **Import error**: invalid JSON, missing required seeds, missing mode

## Error States

- Bundled JSON missing or corrupt → fallback to hardcoded minimal defaults
- Imported JSON missing `light` or `dark` → reject with error message
- Imported JSON missing required palette seeds → reject with error message
- Unparseable JSON → reject with error message

## Permission Request Behavior

Document picker on each platform uses the standard file-access permission model.
No additional permissions needed.

## Design-System Components Needed

- `OpenCodeColors` — canonical OC-2 token API
- `ColorGalleryView` — updated with desktop token names and groups

## Maintainer Workflow

Bundled presets are synced from
[anomalyco/opencode](https://github.com/anomalyco/opencode)
(`packages/ui/src/theme/themes/`) into:

- `shared/theme-presets/` (canonical copy in repo)
- `ios/OpenCode Mobile/OpenCode Mobile/Resources/ThemePresets/`
- `android/app/src/main/assets/theme-presets/`

Run `npm run themes:sync` after bumping the pinned opencode ref in
`scripts/sync-theme-presets.mjs`.
