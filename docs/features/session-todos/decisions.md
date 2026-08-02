# Session Todos Decisions

## Prototype C for mobile chrome

- Decision: Session Todos use a floating pill + view-only bottom sheet
  (prototype C), not composer dock, top strip, or timeline card.
- Stack: Todos pill on top; Scroll to end XOR Changes below; hide the whole
  float stack while the todos sheet is open.
- Why: Compact over chat, matches existing float-stack patterns, keeps
  `todowrite` out of the timeline.
- Ticket: OPE-60 / OPE-97.
- Evidence: `docs/features/session-todos/prototype/VERDICT.md` (throwaway UI on
  `prototype/ope-60-session-todos-ui`).

## Sync lifecycle

- Decision: GET on live (force if cached), SSE `todo.updated` full replace,
  clear local cache on idle, force-GET next live.
- Why: Matches upstream app behavior and avoids stale lists after idle.
- Ticket: OPE-95.

## Naming and presentation rules

- Decision: Product language is **Session Todo List** / **Session Todos** /
  **Session Todo**; locked visibility, preview, progress, and sheet rules live
  in `CONTEXT.md`.
- Why: Grilled against upstream dock semantics and mobile float-stack constraints;
  avoids Tasks / Plan / Checklist confusion.
- Ticket: OPE-60 / OPE-96.

## Feature-local sheet status rows

- Decision: Session Todo sheet rows stay feature-local in Session Chat rather
  than extending `OpenCodeListItem`.
- Why: Dock semantics need checkbox / indeterminate marks and strikethrough
  muted labels; List Item status pills and start decorators do not cover that
  without a one-off API. Promote later if another surface needs the same row.
- Ticket: OPE-97.
