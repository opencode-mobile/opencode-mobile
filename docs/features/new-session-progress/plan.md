# Optimistic New Session Navigation

Ticket: [OPE-131](https://linear.app/opencode-mobile/issue/OPE-131/optimistic-new-session-navigation)

## User Goal

When the user starts a new session from Home or the `/new` command, open Session
Chat immediately and show calm progress while the server creates the session,
instead of leaving Home silent until the network round trip completes.

## In Scope

- Home **New session** navigates to Session Chat immediately with placeholder
  identity.
- Session Chat timeline shows **Starting session…** / **Creating on server…**
  (`OpenCodeStatusStack`) until `POST /session` succeeds.
- After create succeeds, hand off to existing **Loading messages** flow and
  bind the real session id in place (no second navigation animation).
- `/new` client slash command uses the same optimistic replace path.
- Create failure pops back to Home and surfaces inline create error copy.
- Defer model-catalog resolve until after the session exists (parallel with first
  message load / composer bind).
- Disable composer send while timeline is `Creating`.

## Out Of Scope

- Home overlay or button-busy variants (rejected in favor of optimistic navigate).
- Skeleton rows or determinate progress.
- Changing initial-load-indicator surfaces beyond the new Creating copy.

## Server APIs Involved

- `POST /session` — create session (existing).
- `GET` model catalog — deferred until after create (existing resolver).

## Expected Platform Logs

- Home: `home_create_session` with outcome `started` / `success` / `failure` /
  `blocked`.
- Session Chat: `optimistic create start`, `optimistic create success id=…`,
  `optimistic create failed`.

## Domain Objects

- `OptimisticSessionCreate.PLACEHOLDER_SESSION_ID` — navigation placeholder until
  real id is known.
- `OptimisticSessionCreateCoordinator` — reports create failure back to Home.

## Screen Model

**Home:** `createSession(worktree)` sets `pendingOpenSession` immediately with
placeholder id; no in-flight create on Home.

**Session Chat:** new timeline state `Creating`; on promote updates session id
and transitions to `Loading`.

## Native Navigation Entry Points

- Home project footer / dock **New session**.
- Session Chat commands sheet **`new`**.

## Error States

- Disconnected: Home shows existing “Reconnect to create a session” (unchanged).
- Create API failure: pop chat, Home inline create error.

## Design-system Components

- Reuse `OpenCodeStatusStack` with new locked Creating copy (not a new component).
