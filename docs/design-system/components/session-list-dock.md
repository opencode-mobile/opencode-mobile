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
- Optional: `createError` string above the filters.
- States: default. Disabled/loading are not applicable; callers gate actions.
  Empty filter list still shows action row.

## Token Usage

- Surface: Floating Dock chrome (`surface.raised` underlay / glass materials).
- Filters: Filter Chip selected/unselected.
- Actions: iOS 26+ glass buttons; fallback `OpenCodeButton` / Android bordered
  glass-like CTAs.
- Error: `color.error` + body-secondary text.

## Behavior

Stateless. Horizontal chip row scrolls when filters overflow. Add project is a
compact leading control; New session is the full-width primary control beside
it. Create error appears above filters when set.

## Platform Notes

- iOS: `OpenCodeFloatingDock` + `OpenCodeFilterChip` + glass / button CTAs;
  `GlassEffectContainer` groups chips on iOS 26+.
- Android: same structure with Material raised dock and bordered CTAs.

## Review Surfaces

Both Component Galleries include a **Session List Dock** entry with live filter
selection and an optional create-error toggle. Session List gallery also shows
the dock in full home context.

## Parity And Divergence

Shared layout and callbacks. Glass vs raised materials follow Floating Dock
divergence.
