# Project Selection

## User Goal

See curated projects on home, preview recent sessions per project, and open Session Chat
with correct worktree scoping on a single opencode server.

## In Scope

- Curated **added projects** (worktree paths) persisted per server URL.
- Home redesign: app-name top bar, **widgets** action to Component Gallery, project
  sections with letter list items, 3 recent sessions, **See all...** bottom sheet,
  **Browse projects** sheet from `GET /project` catalog.
- Query param **`directory={worktree}`** on all Session and Message repository calls.
- Inline **`GET /global/health`** probe with retry on home load.
- First-launch seed: auto-add boot project worktree when list is empty.
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

- Reuse `OpenCodeListItem` with letter start decorator for projects; title-only for sessions.
