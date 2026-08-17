# Session List Project Picker

Linear: [OPE-160](https://linear.app/opencode-mobile/issue/OPE-160/session-list-project-picker),
[OPE-176](https://linear.app/opencode-mobile/issue/OPE-176/gate-session-list-project-picker-behind-a-setting)

Prototype (locked UX):
[`docs/prototypes/dock-many-projects/index.html`](../../prototypes/dock-many-projects/index.html)
(variant B — scope picker).

## User Goal

Filter home sessions by project without hunting through a clipped chip
scroller once several projects are added.

## In Scope

- Keep the Session List dock **horizontal filter chips** as the default home
  filter.
- Gate the **project picker** behind Settings → Appearance **Project Picker**
  (off by default) so both versions stay available.
- When the setting is on, replace chips with a single **scope control**: letter
  (or All mark), name, compact path or project count, session count, chevron.
- Tap opens a **Filter by project** bottom sheet: **All projects** plus each
  added project (letter, name, compact path, count).
- Tap a row filters the list and dismisses the sheet. No selected checkmark in
  the sheet.
- **Hold** a project row (not All) uses the home Session List long-press pop
  (press scale, then pop) and then the existing Remove-project confirm dialog.
- Confirm still uses *Remove “X” from home? Its sessions stay on the server.*
  with destructive **Remove** / **Cancel**. After confirm: drop the worktree
  from added projects, filter falls back to **All** if needed, snackbar
  *Removed “X” from home*.
- Add project and New session CTAs stay on the dock, unchanged.
- iOS and Android.

## Out Of Scope

- Removing chips while the picker is under evaluation.
- Auto-switching chrome by project count.
- [OPE-156](https://linear.app/opencode-mobile/issue/OPE-156/skip-all-chip-and-new-session-picker-with-one-project)
  single-project chrome collapse (separate ticket; consider when implementing).
- Changing Add project or New session flows.
- Deleting sessions or worktrees on the server.

## Server APIs Involved

- None. Filtering and removal stay client-side curation over the existing
  added-projects list.

## Expected Platform Logs

- No new API calls. Removal remains a local store write; home reload matches
  Add Project / Remove Project today.

## Domain Objects

- **Added Project** (worktree path) as already persisted per server URL.
- Existing `removeWorktree` / `removeProject` Screen Model action.

## Screen Model UI States And User Actions

- Home Session List: `selectedFilterId`, `onFilterSelected(id)` unchanged.
- Home display preference `sessionListProjectPickerEnabled` (default off).
- When enabled: `projectPickerVisible`; open from the dock scope control;
  dismiss on select, overlay tap, or after confirmed remove.
- Pending-removal confirm reuses the existing Remove-project dialog state.
- Hold is a gesture on picker rows, not a new Screen Model field.

## Native Navigation Entry Points

- Home Session List dock (`OpenCodeSessionListDock` on both platforms).
- Settings → Appearance **Project Picker** toggle.
- Project picker bottom sheet presented from home when the setting is on (same
  family as Add project / New session project picker).

## Error States

- Removal is a local write; no new error UI. Home load after removal reuses
  existing probe/load errors.

## Permission Behavior

- Not applicable.

## Design-System Components

- **Session List Dock** — chip row by default; scope-control variant when the
  Project Picker setting is on. Existing, extended.
- **Bottom Sheet** — existing; host the project list when picker chrome is on.
- **List Item** — existing letter decorator + long-press pop; use for picker
  rows (not a new row component).
- **Dialog** — existing destructive confirm; reuse Remove-project copy.
- **Snackbar** — existing; reuse Remove-project confirmation.
- **Filter Chip** — existing; home dock uses it when the picker setting is off.
