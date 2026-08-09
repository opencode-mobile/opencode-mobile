# Optimistic New Session Navigation — Decisions

## Optimistic navigate (variant C)

**Decision:** Navigate to Session Chat immediately on new-session intent. Show
Creating Status Stack until `POST /session` returns, then bind the real session
id and use the existing Loading messages state.

**Alternatives rejected:**

- **Button busy on Home** — still feels frozen on slow connections.
- **Home overlay** — clearer than silent wait but keeps the user on the list.

**Prototype:** published artifact `2253-new-session-progress-ux` (not in repo).
