# OPE-145 — Android scroll-to-end pill blink

## Goal

On Android Session Chat, the Scroll to end pill settles to a single visible or
hidden state after the user stops scrolling. No multi-frame blink near the end.

## In scope

1. Hide the pill on **user** drag/fling only, not on programmatic auto-follow.
2. Keep the pill hidden while auto-follow or a snap-to-end is settling.
3. Add a small reveal/hide slack so threshold jitter cannot flip visibility
   every frame.
4. Unit-test the pure visibility seam.

## Out of scope

- Restoring `AnimatedVisibility` fade on the Session Chat float stack (OPE-106
  ghost-gap risk).
- Changing iOS visibility (already user-scroll + programmatic hide).
- Changing Scroll XOR Review Changes priority.

## Approach

`shouldShowScrollToEndPill` is the seam: user-scroll hide, settling-to-end
suppress, then distance vs reveal with 16dp hide slack. Timeline tracks user
scroll via `NestedScrollConnection` and treats programmatic scroll or
armed-and-unpinned follow as settling.
