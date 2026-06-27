# iOS UI Organization — Progress

**Status:** Shipped on branch `improvement/ope-27-ios-ui-organization`

## Shared Planning

- [x] Audit current iOS vs Android UI folder layout
- [x] Create feature docs (`plan.md`, `progress.md`, `parity-gaps.md`, `decisions.md`)
- [x] Work-plan canvas: `ope-27-ios-ui-organization-work-plan.canvas.tsx`
- [ ] Create Linear issue **OPE-27** in **opencode apps** and set **In Progress** (Linear MCP unavailable)

## iOS

- [x] Define final folder names (`UI/` with PascalCase feature subfolders) — see `decisions.md`
- [x] Move `SessionChatView` + `SessionChatViewModel` to `UI/SessionChat/`
- [x] Move `SessionContextView` + `SessionContextViewModel` to `UI/SessionContext/`
- [x] Move `ProjectListHomeView` + `ProjectListHomeViewModel` to `UI/Main/`
- [x] Move connection screens + `ConnectionSetupViewModel` to `UI/Connection/`
- [x] Move `SettingsView` to `UI/Settings/`
- [x] Move `AppRootView` + `AppRootViewModel` to `UI/Root/`
- [x] Relocate `ui/Markdown/` and `ui/main/` under `UI/`
- [x] Remove `ContentView.swift` and `Item.swift`; `OpenCode_MobileApp` launches `AppRootView` directly
- [x] Move `OpenCodeTheme.swift` + `OpenCodeTypography.swift` into `Theme/`
- [x] Xcode filesystem sync — no `project.pbxproj` edits required
- [x] iOS build + `OpenCode MobileTests` pass

## Android

N/A — reference layout only (`ui/sessionchat/`, `ui/main/`, etc.).

## Test / Preview Status

- [x] `SessionChatViewModelTests` pass after moves
- [x] `SessionContextViewModelTests` pass after moves
- [x] Full `OpenCode MobileTests` suite passes on iPhone 17 Pro simulator

## Documentation

- [x] `docs/architecture.md` — iOS and Android source layout sections

## Remaining Work

- Linear issue sync when MCP is available
- Optional follow-up: move `LetterAccent.swift`, `StartDecorator.swift`, `WorktreeLetterAccent.swift` into `Components/`
- Optional follow-up: split large `SessionChatViewModel.swift` types into `SessionChatModels.swift`
