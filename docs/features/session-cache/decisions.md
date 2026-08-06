# Session Cache — Decisions

## Stale-while-revalidate offline UX

- Date: 2026-06-28 (reaffirmed 2026-08-06)
- Decision: Show last-known session list and opened chat timelines when the
  server is unreachable. Surface stale/offline indicators and block
  send/mutations until reconnected.
- Reason: Instant reopen without pretending the server is live.
- Revisit when: true offline write or conflict resolution is in scope.

## Opened sessions only for message history

- Date: 2026-06-28 (reaffirmed 2026-08-06)
- Decision: Persist message snapshots only for sessions the user has opened in
  Session Chat. Cache full session list metadata per worktree from the last
  successful list fetch.
- Reason: Bound disk growth while still enabling instant home.
- Revisit when: prefetch or full offline browse is required.

## Domain messages only on disk

- Date: 2026-06-28 (reaffirmed 2026-08-06)
- Decision: Store domain `Message` JSON; re-run `MessageContentPreparer` on
  load. Never persist prepared `DisplayMessage` or markdown artifacts.
- Reason: Matches message-store layering; avoids stale display artifacts.
- Revisit when: cold-open latency requires prepared display rows.

## No automatic LRU cap

- Date: 2026-06-28 (reaffirmed 2026-08-06)
- Decision: No LRU eviction in v1. User clears via Settings **Clear Session
  Cache** or **Clear All Data**; show formatted cache size in Settings.
- Reason: User-controlled retention.
- Revisit when: storage pressure requires automatic policy.

## Write timing

- Date: 2026-06-28 (reaffirmed 2026-08-06)
- Decision: Debounced writes (~3s) while Session Chat is open; flush on
  observation teardown / leave / background. Debounce and flush live inside
  `SessionMessageCacheHandle`, not in MessageSync or Screen Models.
- Reason: Avoid streaming write churn; keep write policy local to Session Cache.
- Revisit when: incremental row storage is added.

## Storage technology

- Date: 2026-06-28 (reaffirmed 2026-08-06)
- Decision: Room on Android, SwiftData on iOS; JSON blob columns for list and
  message snapshots as the v1 adapter layout.
- Reason: Aligns with `docs/architecture.md` defaults for durable app-owned
  state.
- Revisit when: query-by-message or per-row storage is needed.

## Worktree removal purge

- Date: 2026-06-28 (reaffirmed 2026-08-06)
- Decision: Purge cached data for a worktree when it is removed from added
  projects.
- Reason: Avoid orphaned cache rows.
- Revisit when: soft-delete or archive semantics are introduced.

## Deep SessionCache caller seam (hybrid)

- Date: 2026-08-06
- Decision: Expose a deep `SessionCache` hot path
  (`seedSessions` / `rememberSessions` / `openMessages` + handle) plus admin
  (`purgeWorktree` / `clearAll` / `estimatedSizeBytes`) to Live Store,
  MessageSync, Settings, Clear All Data, and remove-project. Keep an internal
  `SessionCacheStore` (`load` / `commit` / `retain` / `evict` / `report`) for
  Room, SwiftData, and in-memory adapters only. Do not inject an 8-method CRUD
  store into Live Store or MessageSync.
- Reason: Maximize leverage for common callers; concentrate debounce, opened
  gating, keys, and codec behind one seam; leave room for later kinds/LRU
  without widening caller APIs.
- Revisit when: a second writer (for example prefetch worker) needs a shared
  write scheduler port.

## Cache identity key

- Date: 2026-08-06
- Decision: Scope durable rows by MessageSync-aligned connection id
  (`normalizedBaseUrl|username`) + worktree [+ sessionId], not bare server URL
  alone.
- Reason: Avoid colliding profiles that share a host with different usernames.
- Revisit when: Connection Profile gains a durable opaque id used everywhere.

## Fresh branch from main; do not rebase old tip

- Date: 2026-08-06
- Decision: Implement on `feature/ope-27-session-cache` from current `main`.
  Salvage product decisions, schema idea, Settings UX, and test-double patterns
  from `ivanfenenko/ope-27-session-cache`; do not rebase that tip (~167 commits
  behind; MessageSync and Message models have diverged).
- Reason: Old MessageSync patches and `CachedMessagePayload` codecs are stale.
- Revisit when: N/A (one-time migration choice).

## Observation freshness channel

- Date: 2026-08-06
- Decision: `observeSessions` returns `SessionListSnapshot(sessions, isFromCache)`
  (single channel, not a parallel freshness stream). `MessageListUpdate.Messages`
  and `DisplayMessageListUpdate.Messages` carry `isFromCache` (default `false`);
  DisplayMessagePipeline forwards the flag. MessageSync `note()`s every successful
  Messages publish, including SSE incremental frames.
- Reason: One emission avoids join races; Session Chat observes display updates;
  debounce inside the handle makes frequent `note()` safe. Screen Models never
  call `SessionCache`.
- Revisit when: per-row TTL, a different public display frame type, or coarser
  write policy is required.

## Distinct from model-selection session cache

- Date: 2026-08-06
- Decision: Session Cache (this feature) is durable session list + message
  snapshots. It is not `SessionModelSelectionStore` (composer model/variant
  intent in DataStore / UserDefaults).
- Reason: Same informal name historically; different product objects.
- Revisit when: naming collision causes persistent confusion (then rename one).
