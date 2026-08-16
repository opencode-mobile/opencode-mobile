# OPE-168 — Keep Session Todos visible while unfinished

## Goal

Show the Session Todo List whenever it is unfinished, including after the
session goes idle, matching TUI sidebar visibility.

## In scope

1. Drop the live-only visibility gate. Pill shows when the list is non-empty
   and not fully finished.
2. GET when the session is open; apply SSE `todo.updated` while observing;
   keep the cache on idle; force-GET when the session becomes live again.
3. Update `CONTEXT.md` and `docs/features/session-todos/`.

## Out of scope

- Client edit or check-off
- Changing cancelled-as-finished hide rules
- TUI-style cancelled-still-visible behavior (`status !== completed`)

## Approach

- `SessionTodoList.isVisible` no longer takes `isLive`.
- `SessionTodoLiveStore` seeds on observe, does not clear on idle, and applies
  SSE while the session is open.
