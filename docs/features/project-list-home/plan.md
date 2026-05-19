# Session List Home

## User Goal

Open the app directly into a session list for a selected project so users can
quickly continue active work from their opencode server context instead of
landing in developer-only component gallery screens.

## In Scope

- Replace default home destination with Session List on iOS and Android.
- Hardcode server base URL to `http://127.0.0.1:4096/` for this slice.
- Show fixed selected project context (`opencode-mobile`) until project
  switching is implemented.
- Render session rows from the server via **Session Repository** (`GET /session`).
- Keep component gallery reachable from a secondary "Developer Components"
  affordance.

## Out Of Scope

- Project selection and project switching.
- Connection Profile persistence and secure credential storage (optional Basic
  Auth via environment variable only).
- Message history, prompts, and other session APIs (see
  [`session-repository`](../session-repository/plan.md) boundary).

## Server APIs Involved

- `GET /session` — list sessions for the server's current project (via
  [`SessionRepository`](../../session-repository/plan.md)).

## Domain Objects

- `Session` / `SessionSummary` (id + title) from Session Repository.
- `SessionListHomeState` (iOS home Screen Model states).

## UI States

- Loading.
- Loaded with sessions.
- Empty list.
- Error with fallback copy.

## Error States

- Generic session-load error fallback text.

## Permission Behavior

- Not applicable in this slice.

## Design-System Components

- **List Item:** implemented (`OpenCodeListItem` on Android and iOS; see
  `docs/design-system/components/list-item.md` and component gallery entries).
  Session home uses it for session rows.
