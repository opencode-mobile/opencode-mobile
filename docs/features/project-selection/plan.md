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

- Reuse `OpenCodeListItem` with letter start decorator for projects and session
  rows as needed by Session List.
- **Session List** is the sole home mode (floating dock with filters + Add
  project / New session). The earlier Project Session Selector alternate mode
  was retired — see
  [`retire-project-session-selector`](../retire-project-session-selector/).
  Contract: `docs/design-system/components/session-list.md`.
