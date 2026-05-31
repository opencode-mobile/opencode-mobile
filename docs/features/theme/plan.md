# Theme System

## User Goal

Users can apply the OpenCode desktop theme to the mobile app, and import custom
themes as OC-2 format JSON files, producing a consistent color appearance across
light and dark modes.

## In-Scope

- Load the bundled OC-2 theme JSON at launch on both platforms
- Resolve palette seeds + overrides into a full token dictionary at runtime
- Expose resolved tokens through platform-native color APIs
- Align all app-internal token names with the OpenCode desktop schema
- Import a custom OC-2 JSON via document picker
- Persist imported themes in app-local storage
- Apply imported theme immediately on selection
- Validate theme JSON structure on import

## Out-Of-Scope

- Theme editor or visual builder
- Theme sharing / export
- Server-synced themes
- Dynamic theme switching without app restart (deferred to follow-up)
- Live token preview in a theme picker (deferred to follow-up)

## Server APIs Involved

None. Themes are loaded from bundled or imported JSON only.

## Domain Objects

- `OpenCodeTheme` — top-level theme with metadata and mode variants
- `ThemeVariant` — one light or dark variant containing palette + overrides
- `PaletteSeeds` — the 7 required + 3 optional seed colors
- `ResolvedTokens` — flat dictionary of resolved token name → hex string
- `ThemeManager` — singleton managing current theme, persistence, and resolution

## UI States

- **Loading**: app launch while theme JSON is loaded and resolved
- **Default**: OC-2 theme applied
- **Custom theme active**: imported theme applied
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

- `OpenCodeColors` — already exists, will be renamed and refactored
- `ColorGalleryView` — already exists, will be updated with new token names
