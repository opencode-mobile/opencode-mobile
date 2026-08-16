# Skip All Chip And New-Session Picker With One Project

Linear: [OPE-156](https://linear.app/opencode-mobile/issue/OPE-156/skip-all-chip-and-new-session-picker-with-one-project)

## User Goal

With fewer than two added projects, home should not show a no-op **All** chip or
open a one-row New session picker. One project goes straight to creating a
session; zero projects keep Add project as the path.

## In Scope

- Hide **All** when added-project count is 0 or 1.
  - 1 project: dock shows only that project chip (name, count, selected ×).
  - 0 projects: omit the chip row; keep Add project / New session.
- Skip the New session sheet when count is 0 or 1.
  - 1 project: `createSession` on that worktree immediately.
  - 0 projects: no sheet and no session create; New session stays enabled.
- 2+ projects unchanged: **All** + per-project chips; New session opens the
  picker.
- Selection fallback: when **All** is omitted, default/fallback to the sole
  project id — never `all`.
  - Remove down to 1: select the remaining project.
  - Remove last: existing empty state.
  - Add a second project: **All** reappears; keep the previously selected
    project chip.
- Design-system docs + gallery 1-project sample.
- Tests for tab construction (0 / 1 / 2+) and the skip-picker create path.

## Out Of Scope

- Skipping the picker when 2+ projects are added and a project chip is already
  selected.
- Worktree chips / git worktree UX.
- Disabling New session when there are zero projects.
- Changing picker copy or layout for the 2+ case.
- [OPE-160](https://linear.app/opencode-mobile/issue/OPE-160/session-list-project-picker)
  Session List project picker replacement.

## Server APIs Involved

- None. Reuses the existing home `createSession` path.

## Expected Platform Logs

- Single-project create must not fire `home_open_new_session_project_picker` or
  the picker sheet-present event. Reuse existing `home_create_session`.
- 2+ projects keep the current picker present / select / dismiss events.

## Domain Objects

- Added Project (worktree + display name + session count) as already shown on
  home.
- `SessionListHomeChrome` owns tab construction, filter fallback, and the New
  session action (`none` / `presentPicker` / `create(worktree)`).

## Screen Model UI States And User Actions

- Home still owns selected filter id in the Session List screen.
- New session action is decided from added-project count before any sheet state
  is set.
- Filter fallback: if the stored id is missing from tabs, use the first tab
  (sole project, or **All** when 2+). Never snap to `all` when that tab is
  absent.

## Native Navigation Entry Points

- Home Session List dock: iOS `ProjectListHomeView.homeProjectsList`, Android
  `HomeProjectsContent`.
- Existing optimistic create-session navigation after picker select is reused
  for the single-project path.

## Error States

- Unchanged: create-session error still surfaces on the dock.

## Permission Behavior

- Not applicable.

## Design-System Components

- **Session List** — existing; All is not required when fewer than two
  projects.
- **Session List Dock** — existing; collapse the chip row when `filterTabs` is
  empty. Home still owns the < 2 rule.
