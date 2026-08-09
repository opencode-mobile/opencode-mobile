# Optimistic New Session Navigation — Decisions

## Optimistic navigate (variant C)

**Decision:** Navigate to Session Chat immediately on new-session intent. Show
Creating Status Stack until `POST /session` returns, then bind the real session
id and use the existing Loading messages state.

**Alternatives rejected:**

- **Button busy on Home** — still feels frozen on slow connections.
- **Home overlay** — clearer than silent wait but keeps the user on the list.

**Prototype:** published artifact `2253-new-session-progress-ux` (not in repo).

## Placeholder re-bind never creates twice

**Decision:** The Session Chat ViewModel owns session identity during an
optimistic create. A re-bind that still carries the placeholder id — Android
`LaunchedEffect` re-running after rotation, or iOS `startObserving` re-running
on re-appear — is ignored when the ViewModel already created, or is still
creating, a session for the same worktree. No second `POST /session` is issued
after promotion; the navigation entry intentionally keeps the placeholder id.

**Why not promote the navigation identity to the real id:** replacing the
backstack entry / navigation path would visibly re-animate the push (forbidden
by the plan) and nav3 exposes no in-place entry mutation. The ViewModel is the
source of truth for identity instead.

**Remaining limitation:** a brand-new ViewModel restored from a persisted
placeholder route after process death would create again, orphaning an empty
session on the server. Acceptable for v1.

## Create-failure fan-out

**Decision:** Android collects create failures on a `viewModelScope`-scoped
`SharedFlow` (auto-cleans with the ViewModel). iOS mirrors it with weak-owner
handlers on `OptimisticSessionCreateCoordinator`: each `ProjectListHomeViewModel`
registers with itself as owner, and the coordinator prunes handlers whose owner
is gone before every report, so repeated Home ViewModel creation cannot grow the
handler list or fire stale callbacks.
