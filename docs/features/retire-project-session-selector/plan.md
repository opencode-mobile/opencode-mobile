# Retire Project Session Selector

Linear: [OPE-139](https://linear.app/opencode-mobile/issue/OPE-139/retire-project-session-selector)

## User Goal

Home always shows the Session List with floating dock. The alternate Project
Session Selector mode is gone — no toggle, no dead code path.

## In Scope

- Home always renders Session List (+ dock) on iOS and Android.
- Remove the Project Session Selector component, gallery entries, and design-
  system document.
- Remove selector-owned home state: project focus, See all sessions sheet,
  per-project preview truncation.
- Remove `showSessionListOnHome` from `HomeDisplayPreferences`.
- Update unit tests that asserted focus / preview / See all behavior.
- Record the retirement decision and update stale dual-mode docs.

## Out Of Scope

- Redesigning Session List or dock chrome.
- Changing add-project, remove-project, or new-session project picker flows.
- Changing session actions (rename / fork / archive / delete) on home rows.
- Server API changes.

## Server APIs Involved

- None. Home continues to use existing session/project load paths.

## Expected Platform Logs

- Not applicable for this cleanup (no new API calls or live-update channels).

## Domain Objects

- Home project sections keep identity fields used by Session List filters and
  new-session picker (`worktree`, display name, letter, path, load error,
  session summaries for the cross-project feed).
- Drop selector-only projection fields (`previewSessions` truncation,
  `focusedWorktree`, See all sheet models) where nothing else needs them.

## Screen Model UI States And User Actions

- Drop `focusProject`, `showSeeAllSessions`, `dismissSeeAllSessions`, and
  related UI state from `ProjectListHomeViewModel` / `MainScreenViewModel`.
- Keep create-session error surface on the Session List dock.
- Top bar title is always **Sessions** when connected home content is shown.

## Native Navigation Entry Points

- Home Session List (existing). No selector entry point remains.

## Error States

- Unchanged: probe/load failures, per-project load errors surfaced through
  Session List / empty states, create-session error on the dock.

## Permission Behavior

- Not applicable.

## Design-System Components

- **Session List** / **Session List Dock** — existing; sole home mode.
- **Project Session Selector** — removed from the Component Collection.
