# Session Compact — Progress

## Shared planning

- Prototype variants A/B/C reviewed; **B locked**.
- Feature plan and OPE-116 task plan written.
- Linear: [OPE-116](https://linear.app/opencode-mobile/issue/OPE-116/session-compact-richer-status-chrome)
- Branch: `feature/ope-116-session-compact-status-chrome`

## Design-system dependencies

- None new. Status label copy, snackbar, and optional local ring polish only.

## Android implementation

- Same behavior as iOS via `SessionCompactChrome` + Commands `isCompacting`.
- Effective Compacting… also from `SessionCompactionPresence` (busy + active-turn
  compaction part / assistant mode).
- Domain keeps `MessagePart.Compaction` (not rendered in timeline).
- Unit tests: `SessionCompactChromeTest`, `SessionCompactionPresenceTest`,
  WorkingTurnIndicator compacting cases, SessionChatViewModel compact in-flight /
  failure / remote presence.
- Working-indicator row uses pulse + spacing (shared with Working/Retrying);
  2dp leading pad.
- **Fix:** POST `/session/{id}/summarize` no longer dies on OkHttp’s default 10s
  read timeout (false “Couldn't compact…” while SSE still streamed
  `session.compacted`). API client uses Ktor HttpTimeout; summarize overrides to
  infinite request/socket timeout.
- **Fix:** Clear Compacting… immediately on summarize success; after-fraction
  uses SSE timeline + status refresh (no full message reload gating chrome).

## iOS implementation

- Compacting… override while summarize is in flight.
- Effective Compacting… also from `SessionCompactionPresence`.
- Domain keeps `MessagePart.compaction` (not rendered in timeline).
- Success snackbar with context delta / fallback (initiator only).
- Failure clears compacting and shows error snackbar.
- Unit tests: `SessionCompactChromeTests`, `SessionCompactionPresenceTests`,
  WorkingTurnIndicator compacting cases, SessionChatViewModel compact in-flight /
  failure / remote presence.
- Working-indicator row uses pulse + spacing; 2pt leading pad.
- Summarize URLRequest uses infinite `timeoutInterval` (shared session default
  is 60s).
- **Fix:** After compact success, clear Compacting… before any post-work; do not
  await a full MainActor message refresh for the after-fraction (that froze the
  chat). Summarize runs in `Task.detached` so the long POST does not sit on
  MainActor.

## Blockers

- None.

## Remaining

- [x] Implement optimistic Compacting… chrome (iOS + Android)
- [x] Success snackbar with context delta / fallback
- [x] Clear compacting on failure; keep error snackbar
- [x] Unit tests for label override + snackbar formatting
- [x] Fix Android summarize socket timeout false failure
- [x] Fix iOS post-compact UI freeze / stuck Compacting…
- [x] Remote Compacting… from live busy + compaction-turn signals
- [ ] Device/parity smoke: compact → Compacting… stays → success toast (not fail);
      second client shows Compacting… without local Commands
