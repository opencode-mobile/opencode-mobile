# Remove Project From Home

Linear: [OPE-108](https://linear.app/opencode-mobile/issue/OPE-108/remove-project-from-home)

The dock **×** entry point below is superseded by
[Session List Project Picker](../session-list-project-picker/plan.md)
hold-to-remove ([OPE-160](https://linear.app/opencode-mobile/issue/OPE-160/session-list-project-picker)).
Confirm dialog, snackbar, and local-curation semantics in this slice still
apply.

## User Goal

Remove an added project from the home Session List so home shows only the
projects the user still wants to see — the inverse of Add Project.

## In Scope

- **×** on the **selected** project filter chip in the Session List dock (not on
  **All**, not on unselected project chips). Select a project first to reveal ×.
- Tap **×** → confirm dialog (*"Remove "X" from home? Its sessions stay on the
  server."*) → **Remove** (destructive-primary) / **Cancel**.
- Removing a project drops its sessions from the time ladder and returns the
  filter to **All** (if it was filtering the removed project).
- Removing the last project lands on the existing empty state.
- Pure client-side curation: drop the worktree from the persisted added-projects
  list for the active server URL. No server API. Re-adding the path restores the
  project.
- Session List home mode only.

## Out Of Scope

- Alternate Project Session Selector home mode (retired; Session List only).
- Removing projects from a manage screen or Settings.
- Deleting server sessions or the worktree itself.
- Multiple Connection Profile / server management.

## Server APIs Involved

- None. Removal is local curation only; no new or changed server endpoints.
  Existing session/project calls are unaffected.

## Expected Platform Logs

- Android / iOS: no new API calls for removal. Home reload after removal is the
  same session/project load already used after Add Project; removal itself is a
  local store write and needs no dedicated log.

## Domain Objects

- **Added Project** (worktree path) as already persisted per server URL.
- `ConnectionProfileStore` gains `removeWorktree(serverBaseUrl, worktree)`.

## Screen Model UI States And User Actions

- Session List home (`MainScreenViewModel` Android / `ProjectListHomeViewModel`
  iOS): new action `removeProject(worktree)`.
- Pending-removal confirm state (worktree + display name) presented via
  `OpenCodeDialog`.
- On confirm: persist removal, reload home (skip probe), filter falls back to
  **All** when it matched the removed project.
- Snackbar confirmation: *"Removed "X" from home"*.
- Removing the last project transitions to the existing Empty state.

## Native Navigation Entry Points

- Home Session List dock (Android `OpenCodeSessionListDock` / iOS
  `OpenCodeSessionListDock`), project filter chips.

## Error States

- Removal is a local write and does not fail under normal operation; no dedicated
  error UI. Home load after removal reuses existing probe/load error states.

## Permission Behavior

- Not applicable.

## Design-System Components

- **Filter Chip** — extended with a removable (`×`) variant + `onRemove`:
  existing component, extended (design-system workflow).
- **Session List Dock** — passes `onRemoveProject(worktree)` only for the
  **selected** project chip (not All, not unselected): existing component,
  extended.
- **Dialog** — primary option gains a `destructive` variant for the Remove
  button: existing component, extended.
- **Snackbar** — existing, reused for the confirmation toast.
