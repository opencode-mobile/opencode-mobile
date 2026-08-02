# Session Todos

## User Goal

While a session is live, show the agent-owned **Session Todo List** in Session
Chat so the user can see progress without editing or checking off items.

## In Scope

- Domain models for **Session Todo** / **Session Todo List** on iOS and Android.
- `GET /session/{id}/todo` mapping and live sync: GET when the session becomes
  live (force if cached), SSE `todo.updated` as full-list replace, clear local
  cache on idle, force-GET on the next live turn.
- Session Chat screen-model fields for visibility, active preview, and `N/M`
  progress per locked product rules.
- Floating pill + view-only bottom sheet chrome in Session Chat (child ticket).
- Canonical feature docs under this directory (child ticket).

## Out Of Scope

- Client edit or check-off of Session Todos (no write API).
- Showing or sorting by priority in v1.
- Showing `todowrite` tool parts in the timeline (already hidden).
- Push / background sync of todos.
- Brief all-done close animation timing beyond exposing finished state for the
  chrome ticket.

## Server APIs Involved

- `GET /session/{id}/todo`
- SSE `todo.updated` (`properties.sessionID`, `properties.todos`)

## Domain Objects

- **Session Todo List** — agent-owned, view-only list for one session.
- **Session Todo** — `content`, `status`, `priority`; list order is identity.

## Screen Model

Session Chat exposes todo list state (items, visibility, active preview,
completed/total progress). Pill/sheet actions belong to the chrome ticket;
sync does not add edit actions.

## Native Entry Points

- Session Chat on iOS and Android while the open session is live.

## Error States

- Failed GET while live keeps the previous list (or empty if none); no
  user-facing todo-specific error chrome in the sync ticket.

## Permission Behavior

- Pending permission or question counts as live for fetch/visibility.
- Permission UI wins over the todos sheet (chrome ticket); sync still keeps the
  list while live.

## Design-System Components

- Sync ticket: none (no chrome).
- Chrome ticket: classify pill/sheet primitives when implementing UI.

## Child Tickets

| Ticket | Focus |
| --- | --- |
| [OPE-95](https://linear.app/opencode-mobile/issue/OPE-95/session-todo-list-sync) | Domain + GET/SSE sync + screen-model fields |
| [OPE-97](https://linear.app/opencode-mobile/issue/OPE-97/session-todos-pill-sheet-in-session-chat) | Pill + sheet (blocked by OPE-95) |
| [OPE-96](https://linear.app/opencode-mobile/issue/OPE-96/session-todos-feature-docs) | Finish/maintain feature docs |

Parent: [OPE-60](https://linear.app/opencode-mobile/issue/OPE-60/session-todos).

## References

- Domain terms: `CONTEXT.md` (Session Todo List / Session Todo)
- Research: `docs/research/opencode-session-todos.md`
- Prototype C verdict (throwaway branch): `prototype/ope-60-session-todos-ui`,
  `docs/features/session-todos/prototype/VERDICT.md`
