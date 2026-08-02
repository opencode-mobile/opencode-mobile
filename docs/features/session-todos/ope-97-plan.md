# OPE-97 — Session Todos pill + sheet

## Goal

Ship Session Todos chrome in Session Chat on iOS and Android: floating pill
(`N/M` + active preview) and view-only bottom sheet (progress-first header +
status rows), stacked with existing Scroll-to-end / Changes floats.

## In scope

1. Design-system **Session Todos Pill** (`OpenCodeSessionTodosPill`) + gallery.
2. Feature-local sheet body (status marks / strikethrough) inside
   `OpenCodeBottomSheet` — List Item does not cover checkbox / indeterminate /
   strikethrough dock semantics.
3. Vertical float stack: Todos on top; Scroll XOR Changes below; hide entire
   stack while the todos sheet is open.
4. Permission (and pending question) dismisses / blocks the todos sheet; pill may
   stay under live visibility.
5. Brief all-done hold (~1.5s) before hide while still live; expose
   `sessionTodosFinished` on the screen model for chrome timing.
6. No priority UI; no edit/check-off; `todowrite` remains hidden.

## Out of scope

- Sync / domain mapping (OPE-95)
- Design-system promotion of todo status rows (keep feature-local unless reuse
  appears elsewhere)
