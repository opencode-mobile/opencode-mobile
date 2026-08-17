# Session List

## Intent

Provide the home-scale cross-project session feed: dense rows and a floating
bottom dock with a project scope control plus Add project / New session.

Use as the home session feed. Page chrome (`OpenCodeTopBar` title, live status,
settings) is owned by the home shell, not this component.

## Public API

- Models:
  - `SessionListSession`: `id`, `title`, `worktree`, `letter`, `letterAccent`,
    `subtitle`, `status`, `caption`, optional `updatedAtEpochMs` (used for home
    time-ladder grouping and within-section sort).
  - `SessionListStatus`: `idle` | `running` | `ready`.
  - `SessionListFilterTab`: `id`, `title`, `subtitle`, `count`, optional letter
    + accent. Use id `all` for the cross-project tab (title **All projects**);
    other ids match `SessionListSession.worktree`.
- Required content: `sessions` (may be empty; order before filter is not
  significant — the list filters then groups) and ordered `filterTabs`.
  **All** is required only when two or more projects are added; omit it for
  zero or one project. Zero projects may pass an empty tab list.
- Required filter state: `selectedFilterId` plus `onOpenProjectPicker()`.
  Callers own picker presentation, search, and `onFilterSelected(id)`.
- Optional content:
  - `createError` string shown at the top of the dock when session create fails.
  - `emptyContent` composable / view shown centered above the dock when the
    filtered session list is empty and the caller opts in (for example, connected
    home with no curated projects).
  - iOS-only `showsEmptyContent` gate for rendering `emptyContent`.
  - iOS-only `onRefresh` async callback for pull-to-refresh on the session list.
- Required callbacks:
  - `onSessionSelected(session)`.
  - `onAddProject()`.
  - `onNewSession()`.
  - `onOpenProjectPicker()`.
- Optional callbacks:
  - `onSessionLongPress(session)` on both platforms.
- States: default and empty filtered list (dock and filters remain; optional
  caller-supplied empty content may fill the list region). Loading and
  load-error remain owned by the home screen shell. Page chrome is not part of
  this component.

## Token Usage

- Semantic tokens: background base page fill; list text/status; letter-accent
  badges; dock tokens via Floating Dock and Session List Dock; bottom fade
  via Bottom Fade.
- Component dependencies: `OpenCodeListItem` for rows; `OpenCodeSessionListDock`
  for scope + actions; `OpenCodeBottomFade`. Home shell supplies
  `OpenCodeTopBar` and the Filter by project sheet.
- Typography roles: list-item body/caption; dock scope/button roles via Session
  List Dock.

## Behavior

The component is stateless for session payloads. Callers own captions and
Idle/Running/Ready mapping (Running outranks Ready; Ready uses pill text
**Ready** with `success` tone), filter models/selection, add-project and
new-session flows, and page chrome.

Session List applies the selected project filter, then groups via
`HomeSessionLadder` using `updatedAtEpochMs`, Running/Ready attention, and
`HomeDisplayPreferences.recentWindowHours`. Within each section, rows sort by
`updatedAtEpochMs` descending.

Layout:

1. Scrolling `OpenCodeListItem` rows (letter badge, title, compact path, trailing
   caption + status pill) with leading-inset dividers. Home ladders group as
   Recent → day → week; **Recent** uses a plain section header (same language as
   day/week) and letter-badge **status dots** (bottom-trailing). Day/week rows
   omit the dot.
2. Bottom fade under the dock (`background.base` `0 → 0.7 → 0.7`; top = dock top
   + 16; bottom = physical screen edge).
3. `OpenCodeSessionListDock`: optional create error, scope control, Add
   project + New session.

When `selectedFilterId` is `all`, all sessions are shown. Otherwise only
sessions whose `worktree` equals the selected filter id are shown.

## Platform Notes

- iOS: dense list + Liquid Glass dock/key controls on iOS 26+ with material
  fallbacks below iOS 26. Pull-to-refresh is available when `onRefresh` is
  provided.
- Android: same hierarchy with Material raised glass-like dock; no system Liquid
  Glass. Android `SessionList` does not expose pull-to-refresh in this version.

## Review Surfaces

Both Component Galleries include a realistic mixed Idle/Running/Ready sample with
the scope control and dock actions, plus a **1 project** control that hides All
and shows only that project. Light and dark use the shared gallery theme control.
Edge-to-edge fade is judged full-bleed in the gallery detail. Top bar chrome is
reviewed via Top Bar gallery / home shell, not this entry.

## Parity And Divergence

Shared layout and callbacks. Intentional material difference for glass vs raised
surface is recorded in `../divergences.md`.

## Completion Ledger

- Public API and states: complete.
- Token and typography mapping: complete via dock, fade, and row tokens.
- iOS and Android implementations: complete.
- Gallery and previews: complete for list + dock (no embedded top bar).
- Parity and divergences: intentional glass materials documented.
- Automated checks: covered by platform build/test for affected surfaces.
