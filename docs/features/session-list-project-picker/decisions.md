# Session List Project Picker — Decisions

## Scope picker replaces dock chips

- Date: 2026-08-13
- Decision: home Session List filters with a **single scope control** that
  opens a searchable project sheet, not a horizontal chip scroller.
- Reason: at four or more added projects, named chips clip and the rest of the
  set is invisible until the user hunts. Prototype A (today’s scroller) vs B
  (scope picker) locked B. There is no chips-below-N threshold; the picker is
  the dock filter.
- Prototype: `docs/prototypes/dock-many-projects/`.

## No selected checkmark in the picker

- Date: 2026-08-13
- Decision: picker rows do not show a selected tick. The dock scope control is
  the source of current filter identity.
- Reason: the tick duplicated the dock control and added noise in a list whose
  job is to choose, not to display selection chrome.

## Hold-to-remove, then confirm

- Date: 2026-08-13
- Decision: remove an added project by **long-pressing its picker row** (not
  All). The row uses the home Session List press-then-pop. Confirm with the
  existing Remove-project dialog; do not put × on the dock control.
- Reason: a nested × on the collapsed picker broke layout and looked
  disconnected. Hold matches session delete on home; the dialog keeps removal
  a second, labeled step. Local curation and snackbar copy stay as in
  [`remove-project`](../remove-project/decisions.md).
