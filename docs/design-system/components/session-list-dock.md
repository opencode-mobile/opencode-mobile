# Session List Dock

## Intent

Provide the floating bottom card for Session List home chrome: optional create
error, a single project **scope control**, and Add project / New session
actions.

Use when a screen needs this product dock above a scrolling list. Prefer the
lower-level `OpenCodeFloatingDock` surface only when composing different dock
content.

## Public API

- Required: ordered filter tabs (`id`, `title`, `subtitle`, `count`, optional
  letter + accent), `selectedFilterId`, `onOpenProjectPicker()`,
  `onAddProject()`, `onNewSession()`.
- Optional: `createError` string above the scope control.
- States: default. Disabled/loading are not applicable; callers gate actions.
  Empty `filterTabs` collapses the scope control and still shows the action row.

Tab `id` `all` is the cross-project scope. Other ids match a worktree. Callers
decide whether **All projects** is present; the dock does not insert it.

## Token Usage

- Surface: Floating Dock chrome (`surface.raised` underlay / glass materials).
- Scope control: `text.strong` fill at **0.08**, 14pt corner, letter badge or
  All mark (32 circle + `widgets` grid), body-secondary semibold title, caption
  subtitle (`text.weak`), count pill, `keyboard_arrow_down` chevron.
- Actions: circle Add project + capsule New session, shared height **52**,
  horizontal gap **8**, fill-only `text.strong` at **0.14** on **both**
  platforms (including iOS 26 — dock CTAs intentionally do not use per-button
  Liquid Glass so they match Android weight and alignment).
- Error: `color.error` + body-secondary text.

## Behavior

Stateless. The scope control shows the selected tab: letter (or All mark),
name, compact path or project-count subtitle, session count, chevron. Tap opens
the caller-owned **Filter by project** sheet. Empty `filterTabs` omits the
control (no empty strip). Add project is a compact leading control; New session
is the full-width primary control beside it. Create error appears above the
scope control when set.

Removal is not on the dock. Hold-to-remove lives on project rows in the picker.

## Platform Notes

- iOS: `OpenCodeFloatingDock` + fill-only CTA row. Add project / New session
  stay fill-only (circle + capsule, 52pt) to match Android.
- Android: same structure with Material raised dock and fill-only CTAs at 52dp /
  8dp gap.

## Review Surfaces

Both Component Galleries include a **Session List Dock** entry with a live
scope control that opens the searchable picker, a **1 project** sample (All
absent), and an optional create-error toggle. Session List gallery also shows
the dock in full home context.

## Parity And Divergence

Shared layout and callbacks. Glass vs raised materials follow Floating Dock
divergence.
