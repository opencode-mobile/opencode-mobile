# ADR 0006: Session Cache Stale-While-Revalidate

## Status

Accepted — 2026-08-06

## Context

v1 message storage was explicitly in-memory (`InMemoryMessageStore`) with no
local database. Users still expect instant reopen of home session lists and
previously opened Session Chat timelines when the server is slow or temporarily
unreachable.

`docs/architecture.md` allows Room / SwiftData for durable app-owned state, but
warns not to introduce local persistence solely to cache foreground session
state unless product behavior requires it. Session Cache is that product
requirement.

A June 2026 spike on `ivanfenenko/ope-27-session-cache` proved Room/SwiftData
feasibility with an 8-method CRUD `SessionCacheStore` injected into Live Store
and MessageSync. That tip is far behind `main`: MessageSync gained recovery /
epochs / `SessionMessageEngine`, and domain `Message` / `MessagePart` models
grew (tool richness, file attachments, flags). Rebasing the spike is
high-risk. ADR number 0002 was mistakenly used on that tip; on `main`, 0002 is
already live-stores.

## Decision

Add a **Session Cache** sidecar on both platforms with a deep caller seam and
an internal persistence seam:

1. **External module `SessionCache`:** `seedSessions` / `rememberSessions` /
   `openMessages` (+ handle with `seed` / `note` / `flush`) for Live Store and
   MessageSync; `purgeWorktree` / `clearAll` / `estimatedSizeBytes` for
   Settings, Clear All Data, and remove-project.
2. **Internal `SessionCacheStore`:** `load` / `commit` / `retain` / `evict` /
   `report` behind Room (Android), SwiftData (iOS), and in-memory test
   adapters — not injected into observation loops.
3. **Payload:** session list metadata per connection id + worktree; domain
   `Message` JSON only for opened sessions; re-run `MessageContentPreparer` on
   load; no prepared markdown on disk.
4. **Read path:** emit disk snapshot first, then REST refresh
   (stale-while-revalidate). `InMemoryMessageStore` remains the hot working set.
5. **Write path:** debounced snapshot (~3s) inside the message handle; flush on
   teardown / leave / background.
6. **Offline UX:** show stale content with existing offline indicators; block
   send/mutations until reconnected.
7. **Retention:** no automatic LRU; Settings **Clear Session Cache** and Clear
   All Data; purge on worktree removal.
8. **Identity:** MessageSync-aligned connection id (`baseUrl|username`) +
   worktree [+ sessionId].
9. **Delivery:** implement on `feature/ope-27-session-cache` from current
   `main`; salvage decisions and adapter patterns from the old tip, do not
   rebase it.

## Consequences

**Positive**

- Instant home and chat reopen on cold start.
- Read-only browse when the server is temporarily unreachable.
- Debounce, opened-only policy, keys, and codec stay in one module.
- Internal store can later support more kinds / LRU without widening Live Store
  or MessageSync APIs.
- Clear user control over disk usage.

**Negative**

- Stale rows may appear until reconnect refresh succeeds.
- Disk growth without automatic eviction (mitigated by opened-only messages and
  manual clear).
- First Room/SwiftData dependency in the apps; codec must track Message model
  evolution.

## Alternatives considered

1. **Keep in-memory only** — rejected; fails cold-start and offline-read goals.
2. **Fat CRUD `SessionCacheStore` injected into Live Store / MessageSync**
   (June spike) — rejected as the external seam; shallow, spreads disk details
   into the hottest sync code, and the old MessageSync patches do not apply
   cleanly to current `main`.
3. **Persist prepared `DisplayMessage` / markdown** — rejected; larger DB,
   harder invalidation, duplicates message-store layering.
4. **Automatic LRU eviction** — rejected for v1; prefer manual clear with
   visible cache size.
5. **Bare server URL as cache key** — rejected; collide Connection Profiles that
   share a host with different usernames.
