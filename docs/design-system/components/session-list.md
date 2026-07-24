# Session List

## Intent

Provide the reusable home-scale list of sessions across added projects, sorted by
recency, for opening a session quickly, with sticky footer actions to add a
project or start a new session.

Use this component when a screen needs a cross-project session feed without
owning the visual treatment for session rows or the home action bar.

## Public API

- Models:
  - `SessionListSession`: `id`, `title`, `worktree`, `letter`, `letterAccent`,
    `subtitle`, `status`, `caption`.
  - `SessionListStatus`: `idle` | `running`.
- Models (filters):
  - `SessionListFilterTab`: `id`, `title`, `count`. Use id `all` for the
    cross-project tab; other ids match `SessionListSession.worktree`.
- Required content: ordered `sessions` collection (may be empty) and ordered
  `filterTabs` (at least **All** when filters are shown).
- Required filter state: `selectedFilterId` plus `onFilterSelected(id)`.
- Optional content:
  - `createError` string shown under the footer actions when session create fails.
- Required callbacks:
  - `onSessionSelected(session)`.
  - `onAddProject()` (small add control; opens add-project flow).
  - `onNewSession()` (full-width **New session** control; opens project picker).
  - `onFilterSelected(id)`.
- States: default and empty. Pressed, focused, disabled, loading, and selected
  states are not applicable because rows and owning features expose those
  states. An empty filtered `sessions` collection shows filter tabs and sticky
  footer with no rows.

## Token Usage

- Semantic tokens: list-item surface, text, status (`info`, `interactive`), and
  letter-accent pairs via `OpenCodeListItem`; footer uses button tokens and
  optional error text.
- Component dependencies: `OpenCodeFilterTabs` for project filters;
  `OpenCodeListItem` with letter start decorator, status footer, and caption;
  `OpenCodeButton` `iconSquare` (add) and `fullWidthText` (**New session**).
- Typography roles: list-item typography (`type.heading` title, `type.caption`
  subtitle/status/caption); body-secondary for create error; filter-tab label
  roles via Filter Tabs.

## Behavior

The component is stateless. Callers own sorting, relative-time caption
formatting, Idle/Running mapping, filter tab models and selection, then pass
display-ready `SessionListSession` models. Callers also own the add-project
navigator and the new-session project-picker bottom sheet; this component only
fires filter and footer callbacks.

Layout fills the available height: sticky **Filter Tabs** under the top edge,
session rows scroll in the middle, sticky footer aligned like Project Session
Selector actions (small square control + full-width primary).

When `selectedFilterId` is `all`, all sessions are shown. Otherwise only
sessions whose `worktree` equals the selected filter id are shown.

Each row maps to `OpenCodeListItem` with:

- start decorator: project letter + accent
- title: session name
- subtitle: project root directory
- status: `Idle` with tone `info`, or `Running` with tone `interactive`
- caption: relative time string
- style: `plain`

Selecting a row calls `onSessionSelected` with the full session model so feature
screens can navigate with the correct worktree.

Footer:

- small `iconSquare` add control → `onAddProject`
- full-width **New session** → `onNewSession`
- optional `createError` below the row

## Platform Notes

- iOS: `SessionList` lives under `Components` and uses SwiftUI `ScrollView` +
  sticky footer `HStack` with `OpenCodeButton`.
- Android: `SessionList` lives under `ui/components` and uses Compose
  `LazyColumn` (or scrollable column) with a sticky footer `Row` and
  `OpenCodeButton`.

## Review Surfaces

Both Component Galleries include a realistic mixed Idle/Running sample with
multiple project letters, relative-time captions, and the sticky footer. Light
and dark appearances use the shared gallery theme control.

## Parity And Divergence

iOS and Android expose the same conceptual models and callbacks. Platform type
names differ only where native language conventions require it, such as Swift
closures and Kotlin function types.

## Completion Ledger

- Public API and states: complete.
- Token and typography mapping: complete; row-owned via list item; footer via
  button.
- iOS and Android implementations: complete.
- Gallery and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: covered by platform build/test for affected surfaces.
