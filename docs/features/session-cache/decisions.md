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

## Disk-first home cold start

- Date: 2026-08-06
- Decision: When a Connection Profile has added worktrees, home paints
  `Loaded` from local worktrees + `SessionCache.seedSessions` immediately —
  no full-screen Probing/Loading. Probe runs in the background; failure keeps
  cached home (connection chrome). Live store then SWR-refreshes. Empty
  worktrees show Empty without a network wait; probe failure there can still
  surface ProbeFailed. Soft pull-to-refresh (`preserveContent`) keeps the
  prior network path.
- Reason: Logs showed ~800ms health + REST before UI; Session Chat already
  SWR’d while home still blocked on probe.
- Revisit when: project metadata needs more than worktree-derived names on
  cache seed.

## Smooth cold reopen timeline paint

- Date: 2026-08-06 (revised same day)
- Decision: (1) Disk cache seed paints **HistoryPlainFirst** then TextMate
  enrich (same as large history) — do **not** block Loading on Final-only.
  Warm reopen with complete prepared markdown still uses one **Final**.
  (2) `commands-ready` reuses last `DisplayMessage` snapshot
  (keeps `renderedMarkdown`) instead of bare `Message`. (3) Armed auto-follow
  / re-pin keys off **structureAffecting** only; decorate-only frames still
  drive unarmed anchor restore but do not re-scroll.
- Reason: Final-only on cache left Session Chat on a spinner for seconds while
  TextMate ran (e.g. ~2s after disk seed on a 50-message session). Plain-first
  leaves Loading as soon as structure rows emit; fingerprint-skip keeps enrich
  from being cancelled by identical REST. Timeline Loading chrome is blank
  (no ProgressView / CircularProgressIndicator) so disk decode is not a
  spinner either — content appears when seed paints. Cache/large-history
  HistoryPlainFirst is **newest-first**: prepare and publish the bottom
  markdown message immediately, then fill older history — so the last
  message formats without waiting for the full history parse, and without a
  null-markdown Structure flash.
- Revisit when: disk decode itself (pre-seed) still feels empty too long on
  very large timelines, or plain→Final height shift returns.

## Skip identical REST republish after cache seed

- Date: 2026-08-06
- Decision: After disk seed (or warm fingerprint), if
  `initial-subscribe` REST payload fingerprints equal the published frame,
  still replace/store + `note()` + Healthy, but do **not** emit a second
  `Messages` update. iOS drops the old `|| emittedSeed` force-publish.
- Reason: Unconditional republish cancelled HistoryPlainFirst enrich and
  re-prepared Final, producing a visible Ready→Ready blink on cold reopen
  even when content was unchanged.
- Revisit when: fingerprint misses meaningful display fields and UI stays
  stale after a silent confirm.

## Composer and mutation gate when not Live

- Date: 2026-08-06
- Decision: Disable server mutations when `connectionStatus != Live`
  (Offline or Connecting). Do not OR `messagesFromCache` /
  `sessionsFromCache`. Phase 4 blocks send, abort, rename, permission reply,
  fork, and new session. Allow local/admin offline: remove project, Clear
  Session Cache, Clear All Data, and composer draft/model pick. Stale chrome is
  existing connection status only — no cache subtitle/banner; no reconnect toast
  when `isFromCache` flips false.
- Reason: Mutations need the server; `*FromCache` is true during healthy SWR
  seed and must not block Live. Locked “reuse connection chrome; no new
  components.”
- Revisit when: users confuse Connecting+cache with live data, or offline
  queues land.

## Settings size label format

- Date: 2026-08-06
- Decision: Format `estimatedSizeBytes` in Settings as binary IEC-style
  `"N KB"` / `"N.N MB"` / `"N.N GB"` (whole numbers for B/KB; one decimal for
  MB+). Empty cache shows `"Empty"`, not `"0 KB"`. Humanize in the Settings UI
  layer; keep `SessionCache.estimatedSizeBytes()` as raw bytes.
- Reason: Presentation belongs beside Settings; empty is clearer than zero.
- Revisit when: locales need platform `ByteCountFormatter` strings, or another
  screen shares the label and drifts.

## Clear Session Cache confirmation copy

- Date: 2026-08-06
- Decision: Title `"Clear session cache?"`; body `"Removes cached sessions and
  messages. Your connection and credentials stay."`; confirm `"Clear"`; cancel
  `"Cancel"`.
- Reason: Destructive but scoped; locked no-disconnect must stay explicit.
- Revisit when: cache stores more than list + opened messages.

## Settings size refresh

- Date: 2026-08-06
- Decision: Load size when Settings appears; refresh after a successful Clear
  Session Cache (and after Clear All Data if Settings remains). No polling or
  live observation.
- Reason: Size changes rarely; admin API has no observation channel.
- Revisit when: Settings stays open across long chat writes and stale size
  matters.

## Clear All Data includes Session Cache silently

- Date: 2026-08-06
- Decision: Call `SessionCache.clearAll()` inside Clear All Data orchestration.
  Keep existing confirm copy (`"Clear all data?"` / `"This cannot be undone."`);
  do not add a Session Cache bullet.
- Reason: Matches non-itemized clear-data UX; list subtitle already says
  “cache”.
- Revisit when: Clear All Data copy is redesigned as an inventory.

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
