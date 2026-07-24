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
- [x] Shared **Project Session Selector** on home on iOS and Android, fully
  wired to existing added-project/session data and actions and available in
  the Component Gallery
- [x] Shared **Session List** component: sessions from all added projects sorted
  by recency, with project letter, path subtitle, Idle/Running status, and
  relative-time caption; gallery entry on both platforms
- [x] Settings **Appearance** toggle for Session List vs Project Session
  Selector home mode (default selector; persisted via `HomeDisplayPreferences`)
- [x] `OpenCodeListItem` full-row tap target on iOS and Android (session rows
  and other list items)
- [x] iOS home **Browse projects** and **See all sessions** use
  `OpenCodeBottomSheet` instead of native platform sheets (Android already used
  design-system bottom sheet)

## Session List home mode (in progress)

- [x] Settings toggle switches exclusively between Session List and Project
  Session Selector (not stacked)
- [x] Session List sticky footer: **Add project** + **New session** (project
  picker sheet, then create in chosen worktree); create error under footer
- [x] Shared **Filter Tabs** design-system component (horizontal single-select
  with neutral count badges); gallery entry on both platforms
- [x] Session List filter tabs: **All** + per-project tabs with counts; filters
  visible sessions
- [x] Android Filter Tabs capsule height parity with iOS
- [ ] Manual QA of Session List mode on device/simulator (filter, add project,
  new session, create error)
