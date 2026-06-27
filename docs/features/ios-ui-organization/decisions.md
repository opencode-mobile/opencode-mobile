# iOS UI Organization — Decisions

## Use `UI/` with PascalCase feature subfolders

- Mirror Android's `ui/<feature>/` intent with iOS `UI/<Feature>/` (PascalCase
  matches existing `Components/`, `Domain/`, `Theme/` folders).
- Keep `Components/` separate from feature UI — design-system primitives stay
  reusable across features.

## Move-only refactor first

- Phase 1 is filesystem + Xcode reference moves with no logic changes.
- Optional file splits (e.g. extracting `SessionChatUiState` from
  `SessionChatViewModel.swift`) are follow-ups inside the new folders.

## Single app target

- Do not introduce Swift packages or new targets for this slice; folder
  organization only.

## Theme tokens co-located with Theme/

- `OpenCodeTheme.swift` and `OpenCodeTypography.swift` moved into `Theme/`
  alongside `ThemeManager`, `ThemeResolver`, and preset loading.

## App entry point

- Removed SwiftUI template `ContentView` and unused SwiftData `Item` model.
- `OpenCode_MobileApp` presents `AppRootView` directly.
