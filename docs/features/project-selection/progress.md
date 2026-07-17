# Project Selection — Progress

## Shared planning

- [x] Feature docs and CONTEXT glossary

## Android

- [x] Directory query on APIs; ProjectRepository; ConnectionProfileStore
- [x] Project home ViewModel + UI
- [x] Session Chat worktree navigation
- [x] Unit tests

## iOS

- [x] Same as Android

## Home UX polish

- [x] App-name top bar; three-session previews; **See all...** sheet; per-section
  **New Session** when empty
- [x] Catalog **Browse projects** superseded by
  [Add Project Directory Navigator](../add-project/progress.md)
- [x] `widgets` icon for developer Component Gallery entry (replaced `edit` on home)
- [x] iOS stable home section order (insertion order; fixed Set save + task-group reorder)
- [x] Shared **Project Session Selector** is the only project/session selector
  on home on iOS and Android, fully wired to existing added-project/session
  data and actions and available in the Component Gallery
- [x] `OpenCodeListItem` full-row tap target on iOS and Android (session rows
  and other list items)
- [x] iOS home **Browse projects** and **See all sessions** use
  `OpenCodeBottomSheet` instead of native platform sheets (Android already used
  design-system bottom sheet)
