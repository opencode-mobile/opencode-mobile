# Session Cache — Progress

## Planning

- [x] Product decisions grilled and locked (2026-06-28; reaffirmed 2026-08-06)
- [x] Hybrid architecture locked (deep `SessionCache` + internal store)
- [x] Feature docs under `docs/features/session-cache/`
- [x] Linear [OPE-27](https://linear.app/opencode-mobile/issue/OPE-27/session-cache)
- [x] Branch `feature/ope-27-session-cache` from `main`
- [x] `CONTEXT.md` Session Cache glossary entry
- [x] ADR 0006 stale-while-revalidate session cache

## Design-system dependencies

- None new. Settings uses existing `OpenCodeListItem` + `OpenCodeDialog`.
  Offline chrome reuses existing connection status components.

## Shared / domain

- [x] `SessionCache` + `SessionMessageCacheHandle` on both platforms
- [x] Internal `SessionCacheStore` + versioned JSON codec for current
      `Session` / `Message` models (schemaVersion 1)
- [x] `SessionListSnapshot` / message freshness (`isFromCache`)
- [x] In-memory test adapter

## Android

- [x] Room database + entities/DAO + `RoomSessionCacheStore`
- [x] Hilt binding (`SessionCacheModule`)
- [x] Unit tests (`DefaultSessionCacheTest`)
- [x] `DefaultSessionLiveStore` + `MessageSync` SWR wiring
- [x] Worktree purge + Clear All Data + Settings Session Cache section
- [ ] Composer disabled when offline

## iOS

- [x] SwiftData models + `SwiftDataSessionCacheStore`
- [x] `AppContainer` wiring (`sessionCache` / `sessionCacheStore`)
- [x] Unit tests (`DefaultSessionCacheTests`, 6/6)
- [x] `DefaultSessionLiveStore` + `MessageSync` SWR wiring
- [x] Worktree purge + Clear All Data + Settings Session Cache section
- [ ] Composer disabled when offline

## Blockers

- None. Do not rebase `ivanfenenko/ope-27-session-cache`.

## Remaining Work

1. ~~Implement domain module + codec + Room/SwiftData adapters + tests.~~ (Phase 1)
2. ~~Wire Live Store and MessageSync SWR; expose freshness to home and Session Chat.~~ (Phase 2)
3. ~~Purge on worktree removal; Clear All Data; Settings size + clear.~~ (Phase 3)
4. Disable composer when unreachable; stale/offline chrome; manual offline cold-start verification.
