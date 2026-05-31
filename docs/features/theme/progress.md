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
- [ ] Add `Theme/` files and `ThemeJson.dataset` to Xcode project target
- [ ] Document picker UI for JSON import (follow-up)

## Android

- [x] Bundle `oc-2.json` in `assets/`
- [x] `ThemeModel` — data classes for OC-2 JSON
- [x] `ThemeResolver` — palette-to-token expansion
- [x] `ThemeManager` — load/switch/import/persist
- [x] `Color.kt` — rename tokens, read from resolved dictionary
- [x] `Theme.kt` — update composable to use resolved tokens
- [ ] Call `ThemeManager.initialize(context)` from Application/Activity
- [ ] File picker UI for JSON import (follow-up)

## Component Updates

- [x] `LetterAccent` — no change needed (hardcoded component tokens)
- [x] `ColorGalleryView` / `ColorGalleryScreen` — new token names and groups
- [x] `EventStreamConnectionStateHomeStatus` — updated to use new token API
- [ ] All feature files — old names deprecated, migrate gradually

## Remaining Work

- Add `Theme/` files to Xcode project manually (open project, drag Theme folder)
- Call `ThemeManager.initialize(context)` in Android app entry point
- Add document picker UI for theme import (separate follow-up)
- Migrate deprecated old-name usages to new canonical names across components
