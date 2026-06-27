# iOS UI Organization — Plan

Reorganize the iOS app target so feature screens, screen models, and
feature-local types live in predictable folders aligned with Android's
`ui/<feature>/` layout.

## User Goal

Developers can open the iOS project and immediately find a feature's view,
screen model, and related types together — without scanning a flat app-root
directory mixed with template leftovers and shared helpers.

## In Scope

- Group feature screens under `UI/<Feature>/` (or equivalent) mirroring Android:
  - `SessionChat`
  - `SessionContext`
  - `Main` (`ProjectListHome`)
  - `Connection` (onboarding + setup sheet)
  - `Settings`
  - `Root` (`AppRootView` + `AppRootViewModel`)
- Co-locate each feature's view + screen model (+ feature-local models when split).
- Consolidate scattered UI helpers:
  - `ui/Markdown/` → `UI/Markdown/`
  - `ui/main/` → `UI/Main/` (shared main-shell UI such as connection banner)
- Move root-level design tokens/helpers that belong with theme (`OpenCodeTheme.swift`,
  `OpenCodeTypography.swift`) under `Theme/` or keep documented at root if Xcode
  groups require it.
- Remove or fold SwiftUI template leftovers (`ContentView.swift`, `Item.swift`) if
  unused.
- Update Xcode project references so the app still builds.
- Document the target folder convention in `docs/architecture.md` or this feature's
  `decisions.md`.

## Out Of Scope

- Android reorganization (already structured under `ui/`).
- Splitting large files (`SessionChatViewModel`) into smaller types — optional
  follow-up within each feature folder.
- New Swift packages or multi-target modularization.
- Renaming `*ViewModel` types to `*ScreenModel` (naming alignment is a separate
  decision).

## Server APIs Involved

None — filesystem and Xcode project structure only.

## Domain Objects Involved

None.

## UI States

N/A — no product behavior change.

## Error States

Build failures from broken Xcode file references must be fixed before closing the
slice.

## Design-System Components

No new components. `Components/` and `ComponentGallery/` stay as-is.

## Expected Navigation Entry Points

Unchanged — only file paths move.

## Target Layout

```text
OpenCode Mobile/
├── UI/
│   ├── SessionChat/
│   ├── SessionContext/
│   ├── Main/
│   ├── Connection/
│   ├── Settings/
│   ├── Root/
│   └── Markdown/
├── Components/          # design system (unchanged)
├── ComponentGallery/    # unchanged
├── Domain/              # unchanged
├── Data/                # unchanged
├── DI/                  # unchanged
├── Theme/
├── Network/
└── OpenCode_MobileApp.swift
```

## Verification

- iOS project builds in Xcode (or macOS CI workflow).
- Existing unit tests compile and pass.
- No import path breakage from moved files (Swift module is single target).
