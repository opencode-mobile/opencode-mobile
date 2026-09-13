# Syncing Pill

## Intent

Provide a compact **non-interactive floating status pill** while Session Chat
shows messages that still wait for network confirmation — cached history on
open, or REST catch-up after resume / reconnect. The first product use is the
top-of-timeline **Syncing…** overlay — same floating chrome family as Scroll to
end, but pinned to the top of the list and not tappable.

Feature screens own **when** the pill appears (`syncingIndicatorLabel` /
`isSyncingMessages`). The component only defines **how** it looks when shown.

## Public API

- Variants: one semantic `syncing` presentation (fixed product copy).
- States: visible only. Pressed, disabled, loading, error, empty, selected, and
  focused are not applicable — callers hide the pill when sync completes; the
  pill itself is never a control.
- Required content: status label (product default **Syncing…**).
- Optional content: none.

## Token Usage

- Semantic tokens: `surface-raised-base`, `text-base`, `border-weaker-base`
  (fallback / Android stand-in).
- Component tokens: reuse `actionPill.*` geometry from `../tokens.md`
  (`syncingPill` aliases the same values). Shadow elevation is Android /
  pre–iOS 26 fallback only.
- Typography role: `type.button`.

## Behavior

Presentational only. Does not accept taps. Callers overlay it centered near the
top of the scrolling timeline. Session busy chrome (`Working` / `Retrying` /
`Compacting…`) is the last-timeline-row Session Running chrome, not this
pill.

Accessibility: expose the label as a status announcement (for example
**Syncing…**), not as a button.

## Platform Notes

- iOS 26+: non-interactive `glassEffect(.regular)` capsule via shared
  `OpenCodeFloatingPillChrome`; below iOS 26 raised capsule + border + shadow.
  Leading indeterminate `ProgressView` + label. Hit testing disabled so the pill
  does not steal timeline scrolls.
- Android: Material 3 raised `Surface` stand-in (fill alpha `0.95`, light
  elevation, weaker border) — same floating-chrome family as Action Pill.
  Leading small `CircularProgressIndicator` + label. Not clickable.

## Review Surfaces

- Both Component Galleries include a **Syncing Pill** entry.
- SwiftUI and Compose previews cover light and dark.

## Parity And Divergence

Shared copy, geometry, and non-interactive status meaning. Material mapping
follows the intentional Floating chrome divergence in `../divergences.md`.
