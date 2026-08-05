# Remove Project From Home — Decisions

## × on chips wins the flow prototype

- Date: 2026-08-05
- Decision: the only removal affordance shipped is a **×** on every project
  filter chip in the Session List dock. Tap × → confirm dialog → remove.
- Reason: prototype iteration 2 explored dock overflow (A, hard no), chip
  long-press (B, scrapped), × on chips (C), manage mode (D), and drag-to-remove
  (E). C is the only clearly reasonable UI — always visible, direct, and the
  confirm dialog guards accidental taps.

## Session List mode only

- Date: 2026-08-05
- Decision: v1 ships removal in the **Session List** home mode only; the Project
  Session Selector mode is ignored for now.
- Reason: Session List is the mode actually used today; a selector-mode
  affordance would be speculative chrome. Revisit when the selector is promoted.

## Confirm dialog is destructive-primary

- Date: 2026-08-05
- Decision: the confirm dialog's primary **Remove** option renders with
  destructive emphasis; `OpenCodeDialogOption` gains a `destructive` flag.
- Reason: removal is reversible only by re-adding the path, so the confirm
  action reads as destructive (mirrors the web/Delete session convention).
  This ships an unshipped dialog variant explored in the prototype.

## Removal is local curation; nothing is deleted on the server

- Date: 2026-08-05
- Decision: removing a project drops its worktree from the persisted added
  projects list for the active server URL. No server call, no session deletion.
- Reason: matches the product model — "added projects" is device curation over
  a shared server; sessions stay and the path can be re-added any time.

## Snackbar confirms removal

- Date: 2026-08-05
- Decision: after removal, home shows a transient snackbar *"Removed "X" from
  home"*.
- Reason: the project may not be visible after the filter falls back to All, so
  a short confirmation closes the loop without a heavier affordance.
