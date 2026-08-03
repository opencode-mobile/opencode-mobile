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

## Timeline scrolls under floating composer

- **Decision:** Session Chat matches home Session List floating chrome behavior:
  the timeline fills behind the composer; mid-scroll messages pass under the
  glass; when pinned to the newest message, that message parks **above** the
  composer (readable). Timeline bottom inset equals measured composer-stack
  height + **16** end-pin gap (same token as bottom-fade overhang). Do **not**
  also add the default timeline bottom content padding (`12`) on top — that
  stacked to 28 and felt too open. On iOS, apply clearance via the timeline
  host’s `additionalSafeAreaInsets.bottom` (composer + 16, 168pt fallback
  before measure) with section bottom padding `0` for Session Chat.
- **Why:** Semi-transparent composer only reads as glass while scrolling if
  content can travel behind it; end-pin must keep the latest message clear of
  the chrome without a large empty band above the shell.
- **Date:** 2026-08-02

## Session Chat bottom fade (home parity)

- **Decision:** Use `OpenCodeBottomFade` under the floating composer
  (`composer-stack height + 16` overhang), between timeline and composer in
  z-order — same component and opacity stops as home Session List.
- **Why:** Softens messages as they approach/pass under the glass so the
  underlap matches home instead of a hard cut against empty background.
- **Date:** 2026-08-02
  (replaces the earlier deferral of Session Chat bottom fade.)

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
