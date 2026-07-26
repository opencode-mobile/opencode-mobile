# ADR 0002: Live Stores For Foreground Event Refresh

## Status

Accepted, amended by the Message Store refactor.

## Context

The mobile client uses `GET /global/event` server-sent events as a signal to
refresh session state while the app is foregrounded. REST endpoints remain the
source of truth for messages, session lists, and status labels.

The first implementation routed SSE events through Screen Models: each ViewModel
subscribed to `ForegroundEventStream`, filtered event types, debounced REST
calls, and updated UI state. That worked for Session Chat and home, but duplicated
refresh policy and would not scale cleanly as more screens consume live events
(permissions, abort, diffs).

Plausible alternatives were:

- keep SSE orchestration in every Screen Model;
- extend CRUD repositories with `observe*()` Flow methods on the same type;
- introduce a shared `EventRefreshCoordinator` while ViewModels still orchestrate;
- introduce dedicated Live Store types above unchanged CRUD repositories.

## Decision

Keep foreground refresh policy out of Screen Models. Session lists and status
use dedicated Live Store types:

- `SessionStatusLiveStore` — bulk status map per worktree (`directory`);
- `SessionLiveStore` — session list observation per worktree.

Message observation now uses `MessageSync` and `DisplayMessagePipeline` behind
the `MessageRepository` read API rather than a separate `MessageLiveStore`.
`MessageSync` owns the SSE merge, reconnect/finalization reloads, and watchdog
poll; `DisplayMessagePipeline` owns prepared display-message pacing.

These domain synchronization components inject `ForegroundEventStream`, own
their refresh policy, and expose scoped streams with subscriber-driven
lifecycle (`WhileSubscribed(5000)` on Android). Screen Models must not depend
on `ForegroundEventStream` directly for domain refresh. The home Screen Model
may still observe stream **connection state** for the live-updates banner
because that state is UI chrome, not data refresh policy.

Optimistic send bubbles, `isSending`, composer state, and quiet refresh merge
remain Screen Model responsibilities.

## Consequences

- Refresh policy lives in one place per domain and can be tested without Screen
  Models simulating SSE.
- New live features should add or extend Live Stores instead of wiring SSE in UI
  layer code.
- Transport adapters stay aligned with OpenAPI routes and future codegen;
  `MessageRepository` additionally exposes the domain observation boundary.
- Cross-platform parity requires mirrored Live Store and message-sync behavior
  on iOS and Android.
- Domain synchronization components add types and DI wiring beyond the original
  thin-repository model.
