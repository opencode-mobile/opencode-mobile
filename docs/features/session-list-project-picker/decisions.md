# Session List Project Picker — Decisions

## Scope picker is opt-in; chips stay default

- Date: 2026-08-17
- Decision: home Session List keeps the **horizontal chip scroller** by
  default. The scope picker ships behind Settings → Appearance
  **Project Picker** so both versions stay available.
- Reason: OPE-160 locked prototype B, but the replacement should not become
  the only home chrome until it is explicitly enabled. Chips remain the
  familiar default, including the **All** chip label; picker chrome keeps
  **All projects**.
- Supersedes: 2026-08-13 “Scope picker replaces dock chips”.
- Prototype: `docs/prototypes/dock-many-projects/`.

## No selected checkmark in the picker

- Date: 2026-08-13
- Decision: picker rows do not show a selected tick. The dock scope control is
  the source of current filter identity.
- Reason: the tick duplicated the dock control and added noise in a list whose
  job is to choose, not to display selection chrome.

## Picker sheet has no search

- Date: 2026-08-17
- Decision: Filter by project is a plain list. There is no search field.
- Reason: while chips and picker sit side by side, search is extra chrome the
  chip scroller does not have. A short added-project list does not need it.

## Hold-to-remove, then confirm

- Date: 2026-08-13
- Decision: remove an added project by **long-pressing its picker row** (not
  All). The row uses the home Session List press-then-pop. Confirm with the
  existing Remove-project dialog; do not put × on the dock control.
- Reason: a nested × on the collapsed picker broke layout and looked
  disconnected. Hold matches session delete on home; the dialog keeps removal
  a second, labeled step. Local curation and snackbar copy stay as in
  [`remove-project`](../remove-project/decisions.md).
