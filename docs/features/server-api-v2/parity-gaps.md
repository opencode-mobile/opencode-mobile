# Server API v2 — Parity Gaps

## Temporary: v2 session archive path (both platforms)

- **Expected shared intent:** Home archive removes a session from the active
  list on both v1 and v2 servers.
- **Current iOS / Android:** Both call `POST /api/session/{id}/archive` then
  reload the session. The path matches desktop migration notes but is not in
  the published v2 OpenAPI session index.
- **Affected area:** behavior / error handling on archive against real v2.
- **Owner / next action:** Confirm against the target OpenCode build; if still
  missing, capability-gate archive on v2 in P6 or add the upstream twin.

## Temporary: iOS child/fork directory scoping on v2

- **Expected shared intent:** Child listing and fork honor the active worktree
  the same way on both platforms.
- **Current iOS:** `listChildSessions` / `forkSession` repository methods do not
  take a `directory` argument, so v2 child filter lists without
  `?directory=` and fork omits location query.
- **Current Android:** Passes `directory` through to list/filter and fork
  remotes.
- **Affected area:** behavior under multi-worktree v2 servers.
- **Owner / next action:** Align iOS repository signatures when a v2 multi-root
  case needs it (not blocking single-project home).
