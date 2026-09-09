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
  optional `subtitle`, optional source badge label such as `skill` / `mcp`) and
  overlay-level `onSelect(item)` callback.
- Optional content: empty message when the filtered list is empty while visible.
- Interaction: tap/select an item invokes the overlay `onSelect`; dismiss is
  owned by the caller (for example when the trigger closes).

Pressed / focused / disabled / loading / error: not applicable for v1 (items are
always enabled when shown; loading is handled by the caller before opening).

## Row layout (internal)

Each suggestion uses an **internal row**, not `OpenCodeListItem`:

1. **Primary line:** optional source label as literal bracketed text
   (`[skill]`, `[mcp]`), then the command title (`/skill-name`). Same typography
   as the title; source label uses `text-base` so it stays secondary without
   disappearing on a raised card.
2. **Secondary line:** `subtitle` when present (single line, ellipsis).

## Token Usage

- Semantic tokens: Floating Chrome materials (`surface.raised` / glass; see
  `floating-chrome.md`) and `border-weaker-base` for the panel (same shell
  family as Session Composer); `text-strong` for the title; `text-base` for the
  bracketed source label, subtitle, and empty message. Do not use `text-weak`
  here — Porcelain’s weak grey is too faint on `surface-raised`.
- Typography roles: `type.body` for both the bracketed source label and title;
  `type.bodySecondary` for the subtitle.
- Geometry: panel matching composer chrome family at corner radius 12;
  height hugs the current suggestion rows up to a max of **4 visible items**;
  additional items scroll inside the panel. Max height is budgeted for
  subtitle rows (title + subtitle) so slash-command lists do not clip.
  Internal row padding (not list-item chrome).

## Behavior

- Callers float the panel over the timeline, anchored to the bottom edge above
  the Session Chat composer. It must not take layout height from the timeline
  (overlay, not a stacked sibling that pushes content). The panel’s bottom edge
  stays flush above the composer with keyboard open or closed; do not center it
  in the timeline.
- Panel height follows the filtered result count up to 4 rows (and empty-state
  message). Scrollable only when content would exceed that visible limit.
  Filtering while typing updates the list instantly (no height or item morph
  animation).
- Selecting an item invokes the overlay `onSelect` callback; the caller updates
  composer text and closes the overlay.
- Empty state shows the provided empty message without inventing catalog errors.

Accessibility: each row is a button announcing `[source]` (when present), title,
and subtitle.

## Platform Notes

- iOS: float over the timeline via `.overlay(alignment: .bottom)` above the
  composer; shared `openCodeFloatingChrome` + border; private row view inside
  the overlay file. Use `frame(maxHeight:)` capped at 4 subtitle-budgeted rows
  plus `fixedSize(horizontal:false, vertical:true)` so `ScrollView` hugs when
  fewer rows are shown.
- Android: float over the timeline in a `Box` (`Alignment.BottomCenter`) above
  the composer; shared `OpenCodeFloatingChrome` + outline border; private row
  composable inside the overlay file. Use `heightIn(max = 4 × estimated row)`
  so the column wraps until the visible limit, then scrolls.

## Review Surfaces

- iOS Component Gallery: Composer Suggestion Overlay
- Android Component Gallery: Composer Suggestion Overlay
- Light and dark previews on each platform component.

## Parity And Divergence

Intentional Floating Chrome material difference (iOS Liquid Glass vs Android
raised Surface) matches Session Composer / Floating Dock; see
`../divergences.md`.
