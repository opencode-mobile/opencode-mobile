# Session List Dock

## Intent

Provide the floating bottom card for Session List home chrome: optional create
error, horizontal filter chips, and Add project / New session actions.

Use when a screen needs this product dock above a scrolling list. Prefer the
lower-level `OpenCodeFloatingDock` surface only when composing different dock
content.

## Public API

- Required: ordered filter tabs (`id`, `title`, `count`), `selectedFilterId`,
  `onFilterSelected(id)`, `onAddProject()`, `onNewSession()`.
- Optional: `createError` string above the filters; `onRemoveProject(worktree)`
  for selected-project chip remove.
- States: default. Disabled/loading are not applicable; callers gate actions.
  Empty filter list still shows action row.

## Token Usage

- Surface: Floating Dock chrome (`surface.raised` underlay / glass materials).
- Filters: Filter Chip selected/unselected.
- Actions: iOS 26+ glass buttons (circle Add project / capsule New session at
  height **36**). Below iOS 26 and on Android, matching fill-only CTAs using
  `text.strong` at **0.14**, shared control height **52**, circle + capsule
  shapes (same stand-in family as Filter Chip).
- Error: `color.error` + body-secondary text.

## Behavior

Stateless. Horizontal chip row scrolls when filters overflow. When the selected
filter changes, the row scrolls just enough to keep that chip fully visible
(including after the remove × widens a selected project chip). Add project is a
compact leading control; New session is the full-width primary control beside
it. Create error appears above filters when set.

When `onRemoveProject` is provided, the remove **×** appears only on the
**selected** project chip (never on **All**, never on unselected chips). Tap
selects/filters; × is a deliberate second step after selection.

## Platform Notes

- iOS: `OpenCodeFloatingDock` + `OpenCodeFilterChip` + glass / button CTAs;
  `GlassEffectContainer` groups chips on iOS 26+. Add project clips glass to a
  **circle**; New session clips glass to a **capsule** (same `.buttonStyle(.glass)`
  family). Pre–iOS 26 CTAs mirror Android geometry (52pt height, shared fill)
  instead of mixing `OpenCodeButton` with a shorter pill. Selection uses
  `ScrollViewReader` to bring the selected chip fully on-screen.
- Android: same structure with Material raised dock and fill-only CTAs at 52dp.
  Selection uses `BringIntoViewRequester` inside the horizontal scroll row.

## Review Surfaces

Both Component Galleries include a **Session List Dock** entry with live filter
selection and an optional create-error toggle. Session List gallery also shows
the dock in full home context.

## Parity And Divergence

Shared layout and callbacks. Glass vs raised materials follow Floating Dock
divergence.
