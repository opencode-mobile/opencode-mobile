# Composer Suggestion Overlay

## Intent

Show a compact, composer-anchored list of suggestions while the user types a
trigger such as `/` (server commands) or later `@` / client slash actions.
Feature screens own **when** the overlay appears and what items to show; this
component owns **how** the list looks and behaves above the composer.

## Public API

- Variants: none (single presentation).
- States: **visible**, **empty** (optional empty message), **hidden** (not
  applicable as a rendered state — callers omit the view).
- Required content: ordered suggestion items (`id`, title such as `/name`,
  optional description, optional source badge label such as `skill` / `mcp`,
  `onSelect`).
- Optional content: empty message when the filtered list is empty while visible.
- Interaction: tap/select an item; dismiss is owned by the caller (for example
  when the trigger closes).

Pressed / focused / disabled / loading / error: not applicable for v1 (items are
always enabled when shown; loading is handled by the caller before opening).

## Row layout (internal)

Each suggestion uses an **internal row**, not `OpenCodeListItem`:

1. **Primary line:** optional source label as literal bracketed text
   (`[skill]`, `[mcp]`), then the command title (`/skill-name`). Same typography
   as the title; source label uses a weaker text color.
2. **Secondary line:** description when present (single line, ellipsis).

## Token Usage

- Semantic tokens: `surface-base` and `border-weaker-base` for the panel (same
  shell as Session Composer); `text-strong` for the title; `text-weak` for the
  bracketed source label and description.
- Typography roles: `type.body` for both the bracketed source label and title;
  `type.bodySecondary` for the description.
- Geometry: panel matching composer fill (no raised elevation/shadow); max
  height ~40% of the available space above the composer; internal row padding
  (not list-item chrome).

## Behavior

- Callers float the panel over the timeline, anchored to the bottom edge above
  the Session Chat composer. It must not take layout height from the timeline
  (overlay, not a stacked sibling that pushes content).
- Scrollable when items exceed max height.
- Selecting an item invokes `onSelect` for that item; the caller updates
  composer text and closes the overlay.
- Empty state shows the provided empty message without inventing catalog errors.

Accessibility: each row is a button announcing `[source]` (when present), title,
and description.

## Platform Notes

- iOS: float over the timeline via `.overlay(alignment: .bottom)` above the
  composer; `surface-base` fill + border (same as composer); private row view
  inside the overlay file.
- Android: float over the timeline in a `Box` (`Alignment.BottomCenter`) above
  the composer; `colorScheme.surface` fill + outline (same as composer), no
  tonal/shadow elevation; private row composable inside the overlay file.

## Review Surfaces

- iOS Component Gallery: Composer Suggestion Overlay
- Android Component Gallery: Composer Suggestion Overlay
- Light and dark previews on each platform component.

## Parity And Divergence

None intentional.
