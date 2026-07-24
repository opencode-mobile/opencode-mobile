# Project Selection

## User Goal

See curated projects on home, preview recent sessions per project, and open Session Chat
with correct worktree scoping on a single opencode server.

## In Scope

- Curated **added projects** (worktree paths) persisted per server URL.
- Home redesign: app-name top bar, **widgets** action to Component Gallery, project
  sections with letter list items, 3 recent sessions, **See all...** bottom sheet.
  Project add via catalog **Browse projects** is superseded by
  [Add Project Directory Navigator](../add-project/plan.md).
- Query param **`directory={worktree}`** on all Session and Message repository calls.
- Inline **`GET /global/health`** probe with retry on home load.
- Empty curated list after connect stays empty until manual **Add project**.
- Pass **worktree** into Session Chat navigation.

## Out Of Scope

- Remove project from list
- Manual path entry
- Multi Connection Profile UI
- Keychain credential UI

## Server APIs

- `GET /global/health`
- `GET /project`, `GET /project/current?directory=`
- `GET /session?directory=` and session/message routes with same param

## Design-System

- Reuse `OpenCodeListItem` with letter start decorator for projects; title-only
  for Project Session Selector session previews.
- **Session List** as an alternate home mode (settings toggle) instead of the
  project session selector (`docs/design-system/components/session-list.md`):
  letter, title, project path subtitle, Idle/Running status, relative-time
  caption; **Filter Tabs** for All + per-project scoping; sticky footer
  **Add project** + **New session** (project picker sheet, then create in
  chosen worktree).
