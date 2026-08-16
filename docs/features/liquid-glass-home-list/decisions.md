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

## Follow the true top for newest sessions

- **Decision:** While the user is at the top of home Session List, keep the
  viewport on the true top when newer sessions are prepended. Creating a
  session from home also reveals the newest rows. Scrolling away leaves the
  current rows in place. If home has been hidden for 10 minutes, the next
  appear resets to the true top.
- **Why:** Keyed lazy lists preserve the previously first visible session, so
  new rows appear above the viewport and the list still looks like it is at
  the top. After a long absence the previous row is no longer a useful place.
- **Date:** 2026-08-15
