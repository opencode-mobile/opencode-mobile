# Composer Liquid Glass Decisions

## Outer gutters stay composer-local

- **Decision:** Keep composer outer gutters at content padding `12` and bottom
  `10`. Do not align to Floating Dock float insets (`16` horizontal) in this
  slice.
- **Why:** Materials first; changing gutters would retune timeline outsets,
  suggestion overlay anchoring, and keyboard lift geometry without improving
  the glass family match.
- **Date:** 2026-08-02

## Suggestion overlay matches shell family

- **Decision:** Composer-anchored suggestion overlays (slash / @) use the same
  glass / raised-surface shell family as the composer, not opaque
  `surface-base`.
- **Why:** Opaque panels read as a second material language against the floating
  composer.
- **Date:** 2026-08-02

## Session Chat bottom fade deferred

- **Decision:** Do not add a home-list-style bottom fade under the composer in
  this slice. Leave timeline edge/outsets as today.
- **Why:** Ship shell materials first, then evaluate contrast with message
  bubbles as a follow-up.
- **Date:** 2026-08-02

## Control chrome stays shell-only

- **Decision:** Apply Liquid Glass / raised materials to the outer composer
  shell only. Do not apply iOS 26 glass button styles to composer IconCircles
  or compact actions in this slice.
- **Why:** Matches the home dock’s primary visual win (floating surface) without
  expanding control styling scope; revisit if the shell still reads flat next
  to the dock.
- **Date:** 2026-08-02

## Shared floating chrome helper

- **Decision:** Extract a shared floating chrome helper (modifier / small
  surface helper) used by Floating Dock and Session Composer. Do not wrap the
  composer in `OpenCodeFloatingDock`.
- **Why:** Dock owns dock-specific outer insets and always applies inner `12`
  padding, which would fight composer gutters, outline overlays, and Android
  `AnimatedContent` shell ownership. A shared material recipe avoids drift
  without coupling layouts.
- **Date:** 2026-08-02
