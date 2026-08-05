# Remove Project From Home — Progress

## Shared Planning

- [x] Feature docs under `docs/features/remove-project/`
- [x] Prototype settled the flow: **× on chips** (variant C) wins
- [x] Linear [OPE-108](https://linear.app/opencode-mobile/issue/OPE-108/remove-project-from-home)

## Design System

- [ ] Filter Chip removable (`×`) variant + `onRemove`
- [ ] Session List Dock `onRemoveProject(worktree)` wiring
- [ ] Dialog destructive-primary option
- [ ] Gallery entries + previews

## iOS

- [ ] `removeWorktree` on `ConnectionProfileStore` / `UserDefaultsConnectionProfileStore`
- [ ] `ProjectListHomeViewModel.removeProject(worktree)` + confirm state
- [ ] Dock chips × + confirm dialog in `ProjectListHomeView`/`SessionList`
- [ ] Unit tests

## Android

- [ ] `removeWorktree` on `ConnectionProfileStore` / `DataStoreConnectionProfileStore`
- [ ] `MainScreenViewModel.removeProject(worktree)` + confirm state
- [ ] Dock chips × + confirm dialog in `MainScreen`/`SessionList`
- [ ] Unit tests

## Blockers

- None.

## Remaining Work

- Full implementation and checks per plan.
