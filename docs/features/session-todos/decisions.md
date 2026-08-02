# Session Todos Decisions

## Prototype C for mobile chrome

- Decision: Session Todos use a floating pill + view-only bottom sheet
  (prototype C), not composer dock, top strip, or timeline card.
- Why: Compact over chat, matches existing float-stack patterns, keeps
  `todowrite` out of the timeline.
- Ticket: OPE-60 / OPE-97. Verdict on throwaway branch
  `prototype/ope-60-session-todos-ui`.

## Sync lifecycle

- Decision: GET on live (force if cached), SSE `todo.updated` full replace,
  clear local cache on idle, force-GET next live.
- Why: Matches upstream app behavior and avoids stale lists after idle.
- Ticket: OPE-95.
