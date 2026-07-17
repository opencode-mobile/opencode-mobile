# Add Project Directory Navigator — Progress

Linear: [OPE-63](https://linear.app/opencode-mobile/issue/OPE-63/add-project-directory-navigator)

## Shared planning

- [x] Feature docs under `docs/features/add-project/`
- [x] Mark project-selection catalog browse superseded

## Design system

- [x] Bottom sheet `alwaysFixedHeight` (always 50%) on iOS and Android
- [x] Component Gallery toggle for Fixed 50% + pinned Go up / Current dir sample

## Android

- [x] Path/file API client, domain models, listing policy, repository
- [x] Directory navigator on home (start home, navigate, go up, retry)
- [x] Select current dir validates, persists, focuses; already-added re-focus
- [x] Unit tests (policy + MainScreenViewModel navigator/add)

## iOS

- [x] Same as Android
- [x] Unit tests (DirectoryListingPolicy + ProjectListHomeViewModel)

## Remaining

- None for OPE-63 intended scope

## Follow-up (same branch)

- Fixed home listing: `/file` must use `path=.&directory=<absolute>` because absolute
  `path` values outside the server boot directory return `500 UnknownError`.
