# Theme System — Progress

## Shared Planning

- [x] Research OC-2 format and resolver logic (`research/`)
- [x] Define token rename map
- [x] Update `docs/design-system/tokens.md` with desktop token names
- [x] Create feature tracking docs

## iOS

- [x] Bundle `oc-2.json` in `Resources/` and `Assets.xcassets`
- [x] `ThemeModel` — decodable structs for OC-2 JSON
- [x] `ThemeResolver` — palette-to-token expansion
- [x] `ThemeManager` — `@Observable` load/switch/import/persist
- [x] `OpenCodeColors` — rename tokens, read from resolved dictionary
- [x] `OpenCodeThemeModifier` — update to use new token names
- [x] JSON import infrastructure — `importTheme(from:)`
- [x] `Theme/` files and `ThemeJson.dataset` in app target (Xcode filesystem sync)
- [x] Restore persisted theme on launch (`selectedThemeID` → imported theme)
- [x] Theme presets — 37 bundled presets from opencode desktop (Settings picker)
- [x] Document picker UI in Settings for JSON import
- [x] Migrate deprecated color aliases to canonical OC-2 names (~27 files)

## Android

- [x] Bundle `oc-2.json` in `assets/`
- [x] `ThemeModel` — data classes for OC-2 JSON
- [x] `ThemeResolver` — palette-to-token expansion
- [x] `ThemeManager` — load/switch/import/persist
- [x] `Color.kt` — rename tokens, read from resolved dictionary
- [x] `Theme.kt` — update composable to use resolved tokens
- [x] `ThemeManager.initialize(context)` from `OpenCodeMobileApplication.onCreate`
- [x] Persist imported themes (SharedPreferences) and restore on launch
- [x] File picker UI in Settings for JSON import
- [x] Theme presets — 37 bundled presets from opencode desktop (Settings picker)

## Component Updates

- [x] `LetterAccent` — no change needed (hardcoded component tokens)
- [x] `ColorGalleryView` / `ColorGalleryScreen` — new token names and groups
- [x] `EventStreamConnectionStateHomeStatus` — updated to use new token API
- [x] iOS feature files — migrate from deprecated aliases to canonical names

## Remaining Work (OPE-17)

Branch: `theme/ope-17-persistence-and-import`

### Phase 1 — Persistence loop (ship first)

- [x] iOS: on `ThemeManager` init, read `selectedThemeID` and load from `importedThemes`
- [x] Android: mirror iOS keys in SharedPreferences; persist on import; restore on `initialize`
- [x] Verify `clearAll()` resets both platforms to bundled OC-2

### Phase 2 — Import UI (in-scope per `plan.md`)

- [x] iOS Settings: `fileImporter` for `.json`, call `ThemeManager.importTheme`, show validation errors
- [x] Android Settings: `ActivityResultContracts.OpenDocument`, read URI, call `ThemeManager.importTheme`
- [x] Show current theme name in Settings; success/error feedback inline

### Phase 3 — Init polish

- [x] Android: call `ThemeManager.initialize(this)` from `OpenCodeMobileApplication.onCreate`
- [x] Remove composable `SideEffect` from `OpenCodeTheme` once Application init verified

### Phase 4 — iOS token migration (can land incrementally)

- [x] Replace deprecated `OpenCodeColors` aliases (`textPrimary`, `backgroundPrimary`, etc.) with canonical names
- [x] Prioritize feature screens over gallery previews
- [x] Remove deprecated shims after all call sites migrated

### Out of scope (follow-up issues)

- Live theme switching without restart
- Theme picker with preview
- Theme editor / export / server sync
