# Home Session Actions — Parity Gaps

- **Fork result copy follows each platform's Session Chat.** Android says
  “<title>” is ready. with **Stay here**; iOS says Forked as “<title>”. with
  **Stay**. Home mirrors the wording each platform's fork dialog already uses;
  unify both surfaces together if the copy is ever revisited.
- **Long-press is Session List only.** Neither platform exposes session actions
  from the project-selector “Show all sessions” sheet, because
  `OpenCodeBottomSheetEntry` has no long-press affordance. Revisit only if the
  sheet becomes a primary session surface.
- **Fork gate ignores the session revert boundary.** Session Chat Commands
  computes fork visibility against the active revert message id; home passes no
  revert boundary so it only needs a single fetched user message. A home row for
  a fully reverted session can therefore offer Fork where Commands would hide
  it. Accepted to keep the sheet to one request per long-press.
