# OPE-167 — Move Session Chat pills to the top overlay

## Goal

Keep the last Session Chat message readable. Todos, Review Changes, and Syncing
sit in a top overlay stack. Only Scroll to end stays at the bottom.

## In scope

1. Top-center overlay stack, 10pt from the timeline top, 8pt between siblings:
   Syncing, then Session Todos, then Review Changes. Compose only visible pills.
2. Bottom overlay: Scroll to end only, still using `bottomOverlayInset`
   (composer + 16 end-pin gap; no extra `scrollToEndPillBottomPadding`).
3. Review Changes shows whenever the latest completed turn changed at least one
   file. It is no longer XOR with Scroll to end and is not gated on pin.
4. Hide Todos and Review while the Session Todos sheet is open. Syncing may stay.
5. Update Session Chat / Session Todos docs, `CONTEXT.md`, and the Review
   Changes caller contract.

## Out of scope

- Reserved strip, folded top-bar chrome, or combined island (prototype B–D).
- Moving composer chips, permission/question composer states, or the Working row.
- Restoring float-stack fade / `AnimatedVisibility`.
- Changing pill chrome, copy, or individual component APIs.

## Approach

Reuse the existing capsules. Split the Session Chat float stack into a top
overlay and a bottom Scroll to end pill. `ReviewChangesPillVisibility` checks
file count only.

Locked visual: prototype variant A in `docs/prototypes/pills-on-top/`.
