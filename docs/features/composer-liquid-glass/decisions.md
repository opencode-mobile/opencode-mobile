# Composer Liquid Glass Decisions

## Outer gutters match Floating Dock bottom

- **Decision:** Session Composer outer horizontal gutters stay at content
  padding `12`. Bottom outer inset is `4` — same as Floating Dock — so Session
  Chat sits as low as home. Session Chat bottom fade also extends into the
  home-indicator safe area like home Session List (`offset` +
  `ignoresSafeArea`).
- **Why:** The previous composer bottom `10` read as extra empty band under the
  shell versus the home dock.
- **Date:** 2026-08-03
  (revises the earlier keep-bottom-10 gutters decision.)

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

## Control chrome: glass Choice Badge for pickers

- **Decision:** Session Composer model, mode, and thinking-effort (variant)
  pickers use `OpenCodeChoiceBadge` **glass** variant in the **unselected**
  (clear / clickable) state. IconCircle attach / commands / send / stop stay on
  existing button chrome for this slice. Do not reuse `OpenCodeFilterChip`
  here — Filter Chip owns count badges and dock filter semantics.
- **Why:** Choice Badge is the composer’s selection control; a glass variant
  keeps one component API while matching home glass language. Filter Chip stays
  for dock filters with optional counts.
- **Date:** 2026-08-03
  (revises the earlier shell-only control decision for these three pickers.)

## Picker chip row scrolls only on overflow

- **Decision:** The model / mode / thinking-effort chip row is horizontally
  scrollable only when content is wider than the composer. iOS uses
  `scrollBounceBehavior(.basedOnSize)`; Android enables `horizontalScroll`
  only after measuring content wider than the container.
- **Why:** A always-on horizontal ScrollView rubber-bands / captures drag when
  three short chips already fit.
- **Date:** 2026-08-03

## Shared floating chrome helper

- **Decision:** Extract a shared floating chrome helper (modifier / small
  surface helper) used by Floating Dock and Session Composer. Do not wrap the
  composer in `OpenCodeFloatingDock`.
- **Why:** Dock owns dock-specific outer insets and always applies inner `12`
  padding, which would fight composer gutters, outline overlays, and Android
  `AnimatedContent` shell ownership. A shared material recipe avoids drift
  without coupling layouts.
- **Date:** 2026-08-02
