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
- Required content: ordered suggestion items (`id`, title, optional subtitle,
  optional badge label, `onSelect`).
- Optional content: empty message when the filtered list is empty while visible.
- Interaction: tap/select an item; dismiss is owned by the caller (for example
  when the trigger closes).

Pressed / focused / disabled / loading / error: not applicable for v1 (items are
always enabled when shown; loading is handled by the caller before opening).

## Token Usage

- Semantic tokens: `surface-raised-base`, `border-weaker-base`, `text-strong`,
  `text-weak`.
- Typography roles: list title/subtitle via `OpenCodeListItem` mappings.
- Geometry: raised panel with standard list-item rows; max height ~40% of the
  available space above the composer.

## Behavior

- Anchored immediately above the Session Chat composer (or gallery mock
  composer).
- Scrollable when items exceed max height.
- Selecting an item invokes `onSelect` for that item; the caller updates
  composer text and closes the overlay.
- Empty state shows the provided empty message without inventing catalog errors.

Accessibility: each row is a button with the title (and badge when present).

## Platform Notes

- iOS: SwiftUI overlay in a `VStack` above the composer; raised material
  background and clip.
- Android: Compose `Surface` above the composer in the same column; Material 3
  raised surface + outline.

## Review Surfaces

- iOS Component Gallery: Composer Suggestion Overlay
- Android Component Gallery: Composer Suggestion Overlay
- Light and dark previews on each platform component.

## Parity And Divergence

None intentional.
