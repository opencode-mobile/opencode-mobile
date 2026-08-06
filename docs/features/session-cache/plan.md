# Session Cache

## User Goal

Reopen the home session list and previously opened Session Chat timelines
immediately on cold start, even when the server is slow or unreachable, then
refresh from the server when connectivity returns.

## In Scope

- Deep `SessionCache` module (hot path) with internal persistence adapters
  (Room on Android, SwiftData on iOS, in-memory for tests).
- Session list metadata snapshots per Connection Profile connection id +
  worktree, from the last successful `GET /session`.
- Domain `Message` history snapshots only for sessions opened in Session Chat.
- Stale-while-revalidate in `SessionLiveStore` and `MessageSync`: emit disk
  first, then refresh from REST; keep `InMemoryMessageStore` as the live
  working set.
- Debounced disk writes (~3s) while a session is open; flush on observation
  teardown / leave / background (owned inside `SessionMessageCacheHandle`).
- Settings **Session Cache** section: formatted size + **Clear Session Cache**
  confirmation (does not disconnect or wipe credentials).
- Purge worktree cache when an added project is removed.
- Include Session Cache in Clear All Data orchestration.
- Offline / stale indicator when showing cache without a successful refresh;
  block send / mutations until reconnected.
- Versioned JSON codec for current domain `Session` / `Message` models;
  corrupt or unknown schema → treat as miss.
- Feature docs, `CONTEXT.md` glossary term, and ADR 0006.

## Out Of Scope

- Paginated history (`limit` / `before` / cursors).
- Persisting SSE patch state separately from full snapshots.
- Automatic LRU eviction.
- Prepared `DisplayMessage` / markdown artifacts on disk.
- Caching diffs, permissions, session status maps, or todos.
- Prefetch of never-opened sessions (retain hooks may exist internally later).
- True offline write / conflict resolution.
- Rebasing or merging branch `ivanfenenko/ope-27-session-cache` tip onto main
  (salvage decisions and patterns only; implement on a fresh branch from main).
- Confusing this feature with model-selection’s `SessionModelSelectionStore`.

## Server APIs Involved

- `GET /session` — seeds session list snapshots after successful refresh.
- `GET /session/:id/message` — seeds message snapshots after successful refresh.

No new server endpoints. Session Cache never talks to the network directly.

## Expected Platform Logs

- Cache hit / miss / write / flush / purge / clear at a stable tag (for example
  `SessionCache`), without message bodies or credentials.
- Existing repository / SSE logs remain authoritative for network paths.

## Domain Objects

### External seam (callers: Live Store, MessageSync, Settings, Clear All Data,
remove-project)

```text
SessionCache
  seedSessions(worktree) -> List<Session>?
  rememberSessions(worktree, sessions)
  openMessages(sessionId, worktree) -> SessionMessageCacheHandle
  purgeWorktree(serverBaseUrl, worktree)   // admin; caller has URL
  clearAll()
  estimatedSizeBytes() -> Long

SessionMessageCacheHandle
  seed() -> List<Message>?     // mark opened + disk read
  note(messages)               // debounced write-behind
  flush() / close()            // cancel debounce + write now
```

Hot-path methods resolve the active Connection Profile connection id
(`baseUrl|username`, aligned with `MessageSync`) internally. They no-op when
no connection is active.

### Internal seam (Room / SwiftData / InMemory only)

```text
SessionCacheStore
  load / commit / retain / evict / report
```

Not injected into Live Store or MessageSync.

### Observation freshness

- `SessionListSnapshot(sessions, isFromCache)` (or equivalent freshness).
- `MessageListUpdate.Messages(..., isFromCache)` (or equivalent freshness).

Produced by Live Store / MessageSync when emitting seed vs network frames.
Screen Models must not call `SessionCache` directly.

## UI States

- Home: may show cached session list with stale/offline chrome when refresh
  fails or has not completed.
- Session Chat: may show cached timeline with stale/offline chrome; composer
  send disabled when server unreachable.
- Settings: Session Cache section idle; size label; Clear Session Cache
  confirmation dialog.

## User Actions

- Automatic: home observe and Session Chat open trigger seed / remember / note.
- `clearSessionCacheTapped` / `clearSessionCacheConfirmed` /
  `clearSessionCacheCancelled` in Settings.
- Existing Clear All Data and remove-project flows also purge cache.

## Error States

- No disk cache and network failure → existing load-error paths.
- Disk cache present and network failure → show stale content; no blocking
  error for the timeline/list.
- Corrupt / unknown codec → miss (optionally delete bad row); never crash
  observation loops.
- Disk write / admin failures → best-effort log; swallow (match Clear All Data).

## Permission Request Behavior

Not applicable (local persistence only; no permission requests).

## Design-System Components

| Element | Classification |
| --- | --- |
| Settings Session Cache rows | Existing `OpenCodeListItem` |
| Clear Session Cache confirmation | Existing `OpenCodeDialog` |
| Offline / connection chrome | Existing connection status components |
| New reusable components | None expected |

## Platform Entry Points

- iOS / Android: automatic via home session list and Session Chat open.
- iOS / Android Settings → **Session Cache** section (size + Clear Session Cache).
- Clear All Data and remove-project purge paths.

## Architecture Notes

```text
Screen Models ──observe──► LiveStore / MessageSync ──hot path──► SessionCache
Settings / Clear All / removeWorktree ──admin──► SessionCache
                                                      │
                                         SessionCacheStore (internal)
                                                      │
                                              Room / SwiftData
```

Implementation starts from `feature/ope-27-session-cache` off current `main`.
Salvage product decisions, two-table JSON blob schema idea, Settings UX, and
in-memory test-double patterns from the abandoned
`ivanfenenko/ope-27-session-cache` tip; redesign codecs and LiveStore/MessageSync
hooks against today’s message engine.
