# Remove Project From Home — Progress

## Shared Planning

- [x] Feature docs under `docs/features/remove-project/`
- [x] Prototype settled the flow: **× on chips** (variant C) wins
- [x] Linear [OPE-108](https://linear.app/opencode-mobile/issue/OPE-108/remove-project-from-home)

## Design System

- [x] Filter Chip removable (`×`) variant + `onRemove`
- [x] Session List Dock `onRemoveProject(worktree)` wiring
- [x] Dialog destructive-primary option
- [x] Gallery entries + previews

## iOS

- [x] `removeWorktree` on `ConnectionProfileStore` / `UserDefaultsConnectionProfileStore`
- [x] `ProjectListHomeViewModel.removeProject(worktree)` + confirm state
- [x] Dock chips × + confirm dialog in `ProjectListHomeView`/`SessionList`
- [x] Unit tests

## Android

- [x] `removeWorktree` on `ConnectionProfileStore` / `DataStoreConnectionProfileStore`
- [x] `MainScreenViewModel.removeProject(worktree)` + confirm state
- [x] Dock chips × + confirm dialog in `MainScreen`/`SessionList`
- [x] Unit tests

## Blockers

- None.

## Remaining Work

- None for the planned slice.
