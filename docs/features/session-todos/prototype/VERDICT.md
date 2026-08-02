# Prototype verdict — Session Todos UI

- **Date:** 2026-08-02
- **Question:** How should the view-only session todo list sit in mobile Session Chat?
- **Branch:** `prototype/ope-60-session-todos-ui` (throwaway; not for `main`)
- **Run:** `npm run prototype:session-todos` → http://localhost:5180/?variant=C

## Verdict

**Winner: C — Floating pill + bottom sheet**

- Compact floating pill over the timeline (above the composer), showing progress
  (`N/M`) and the active item preview.
- Tap opens a view-only bottom sheet with the full list.
- Pill hides while the sheet is open.

### Floating stack with existing Session Chat pills

Reuse the same band above the composer:

1. **Todos pill** on top (when todos are visible)
2. **Scroll to end** *or* **Changes** below it (existing XOR rule unchanged)

When the todos sheet is open, hide the entire float stack.

## Rejected for now

| Variant | Why not |
| --- | --- |
| A Composer dock | Feels heavier on mobile; competes with composer chrome |
| B Top progress strip | Steals top-of-screen attention from session status |
| D Timeline task card | Conflicts with “don’t surface todos via timeline tool parts”; denser stream |

## Folded into feature docs

Product rules and chrome intent are recorded in
`docs/features/session-todos/plan.md` and `decisions.md`, with domain terms in
`CONTEXT.md`. Do not merge the throwaway prototype branch into `main`.
