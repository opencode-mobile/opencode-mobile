# Liquid Glass Home List Decisions

## Variant C is the accepted home list chrome

- **Decision:** Dense session list + floating bottom dock (filters + actions) is
  the production direction for Session List home mode.
- **Why:** Validated on `prototype/liquid-glass`; clearer primary actions and
  filters than a sticky footer under the previous Session List chrome.
- **Date:** 2026-07-24

## Bottom fade geometry

- **Decision:** Fade uses `background-base` at opacity stops `0 → 0.7 → 0.7`.
  Top of fade is dock card top + 16pt/dp. Bottom of fade is the physical screen
  edge. Dock stays above the home indicator / navigation bar; fade sits between
  list and dock in z-order.
- **Why:** Softens list under the floating card without covering the card or
  leaving a safe-area gap at the bottom edge.
- **Date:** 2026-07-24

## Dock underlay opacity (iOS)

- **Decision:** Keep dock underlay at `surface-raised-base` opacity `0.92` under
  glass (not used as the primary transparency control for the glass material).
- **Why:** Glass blur/refraction is system-owned; underlay only tints solidity.
- **Date:** 2026-07-24
