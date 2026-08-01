# Session List

## Intent

Provide the home-scale cross-project session feed: dense rows and a floating
bottom dock with project filter chips plus Add project / New session.

Use when Session List home mode is on. Page chrome (`OpenCodeTopBar` title,
live status, settings) is owned by the home shell, not this component.

## Public API

- Models:
  - `SessionListSession`: `id`, `title`, `worktree`, `letter`, `letterAccent`,
    `subtitle`, `status`, `caption`.
  - `SessionListStatus`: `idle` | `running`.
  - `SessionListFilterTab`: `id`, `title`, `count`. Use id `all` for the
    cross-project tab; other ids match `SessionListSession.worktree`.
- Required content: ordered `sessions` (may be empty) and ordered `filterTabs`
  (at least **All** when filters are shown).
- Required filter state: `selectedFilterId` plus `onFilterSelected(id)`.
- Optional content:
  - `createError` string shown at the top of the dock when session create fails.
  - `emptyContent` composable / view shown centered above the dock when the
    filtered session list is empty and the caller opts in (for example, connected
    home with no curated projects).
- Required callbacks:
  - `onSessionSelected(session)`.
  - `onAddProject()`.
  - `onNewSession()`.
  - `onFilterSelected(id)`.
- States: default and empty filtered list (dock and filters remain; optional
  caller-supplied empty content may fill the list region). Loading and
  load-error remain owned by the home screen shell. Page chrome is not part of
  this component.

## Token Usage

- Semantic tokens: background base page fill; list text/status; letter-accent
  badges; dock and chip tokens via Floating Dock and Filter Chip; bottom fade
  via Bottom Fade.
- Component dependencies: `OpenCodeListItem` for rows; `OpenCodeSessionListDock`
  for filters + actions; `OpenCodeBottomFade`. Home shell supplies
  `OpenCodeTopBar`.
- Typography roles: list-item body/caption; dock chip/button roles via Session
  List Dock.

## Behavior

The component is stateless for data. Callers own sorting, captions, Idle/Running
mapping, filter models/selection, add-project and new-session flows, and page
chrome.

Layout:

1. Scrolling `OpenCodeListItem` rows (letter badge, title, compact path, trailing
   caption + status pill) with leading-inset dividers.
2. Bottom fade under the dock (`background.base` `0 → 0.7 → 0.7`; top = dock top
   + 16; bottom = physical screen edge).
3. `OpenCodeSessionListDock`: optional create error, horizontal filter chips, Add
   project + New session.

When `selectedFilterId` is `all`, all sessions are shown. Otherwise only
sessions whose `worktree` equals the selected filter id are shown.

## Platform Notes

- iOS: dense list + Liquid Glass dock/chips/key controls on iOS 26+ with material
  fallbacks below iOS 26.
- Android: same hierarchy with Material raised glass-like dock; no system Liquid
  Glass.

## Review Surfaces

Both Component Galleries include a realistic mixed Idle/Running sample with
filters and dock actions. Light and dark use the shared gallery theme control.
Edge-to-edge fade is judged full-bleed in the gallery detail. Top bar chrome is
reviewed via Top Bar gallery / home shell, not this entry.

## Parity And Divergence

Shared layout and callbacks. Intentional material difference for glass vs raised
surface is recorded in `../divergences.md`.

## Completion Ledger

- Public API and states: complete.
- Token and typography mapping: complete via dock, chip, fade, and row tokens.
- iOS and Android implementations: complete.
- Gallery and previews: complete for list + dock (no embedded top bar).
- Parity and divergences: intentional glass materials documented.
- Automated checks: covered by platform build/test for affected surfaces.
