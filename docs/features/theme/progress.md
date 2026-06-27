# Theme System — Progress

**Status:** Shipped (OPE-17) — [PR #15](https://github.com/ivanfenenko/opencode-mobile/pull/15)

## Shared Planning

- [x] Research OC-2 format and resolver logic (`research/`)
- [x] Define token rename map
- [x] Update `docs/design-system/tokens.md` with desktop token names
- [x] Create feature tracking docs
- [x] Sync 37 desktop theme presets from opencode (`npm run themes:sync`)

## iOS

- [x] Bundle theme presets in `Resources/ThemePresets/` (Xcode copies JSON into app bundle)
- [x] `ThemeModel` — decodable structs for OC-2 JSON
- [x] `ThemeResolver` — palette-to-token expansion
- [x] `ThemeManager` — `@Observable` load/switch/import/persist
- [x] `OpenCodeColors` — canonical OC-2 token names, read from resolved dictionary
- [x] `OpenCodeThemeModifier` — update to use new token names
- [x] JSON import infrastructure — `importTheme(from:)`
- [x] `Theme/` files and `ThemeJson.dataset` in app target (Xcode filesystem sync)
- [x] Restore persisted theme on launch (`selectedThemeID` → bundled or imported theme)
- [x] Theme presets — Settings theme row opens bottom-sheet picker (37 bundled presets)
- [x] Document picker UI in Settings for custom JSON import
- [x] Migrate deprecated color aliases to canonical OC-2 names (~27 files)
- [x] Bundle loader tolerates Xcode flattening preset JSON into bundle root

## Android

- [x] Bundle theme presets in `assets/theme-presets/`
- [x] `ThemeModel` — data classes for OC-2 JSON
- [x] `ThemeResolver` — palette-to-token expansion
- [x] `ThemeManager` — load/switch/import/persist
- [x] `Color.kt` — rename tokens, read from resolved dictionary
- [x] `Theme.kt` — update composable to use resolved tokens
- [x] `ThemeManager.initialize(context)` from `OpenCodeMobileApplication.onCreate`
- [x] Persist imported themes (SharedPreferences) and restore on launch
- [x] File picker UI in Settings for custom JSON import
- [x] Theme presets — Settings theme row opens bottom-sheet picker (37 bundled presets)

## Component Updates

- [x] `LetterAccent` — no change needed (hardcoded component tokens)
- [x] `ColorGalleryView` / `ColorGalleryScreen` — new token names and groups
- [x] `EventStreamConnectionStateHomeStatus` — updated to use new token API
- [x] iOS feature files — migrate from deprecated aliases to canonical names

## Follow-up (out of scope for OPE-17)

- Live theme switching without restart
- Theme picker with color swatch preview
- Theme editor / export / server sync
