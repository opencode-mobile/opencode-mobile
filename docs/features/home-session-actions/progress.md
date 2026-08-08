# Home Session Actions — Progress

## Shared planning

- [x] Plan accepted for OPE-120 long-press home session actions
- [x] Linear OPE-120 In Progress; docs path linked

## Design-system dependencies

- [x] `OpenCodeListItem` optional long-press (iOS + Android + docs)
- [x] `OpenCodeBottomSheetEntry` optional destructive (iOS + Android + docs)

## iOS

- [x] `SessionList` / `OpenCodeListItem` long-press → action sheet
- [x] `ProjectListHomeViewModel` rename / fork / archive / delete
- [x] Fork gate via message fetch + `ClientSlashActionCatalog`
- [x] Dialogs + snackbar + fork Open/Stay on home

## Android

- [x] `SessionList` / `OpenCodeListItem` long-press → action sheet
- [x] `MainScreenViewModel` rename / fork / archive / delete
- [x] Fork gate via message fetch + `ClientSlashActionCatalog`
- [x] Dialogs + snackbar + fork Open/Stay on home

## Blockers

- None

## Remaining work

- Manual device pass on both platforms (long-press feel, sheet and dialog copy)