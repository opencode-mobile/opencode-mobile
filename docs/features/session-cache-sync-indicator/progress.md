# Session Cache Sync Indicator

Ticket: [OPE-137](https://linear.app/opencode-mobile/issue/OPE-137/session-cache-sync-indicator-top-pill)
(supersedes in-chat row from [OPE-132](https://linear.app/opencode-mobile/issue/OPE-132/session-cache-sync-indicator))

## Status

Implemented top-of-list **Syncing…** pill (prototype Variant A — mirror of
Scroll to end chrome, non-interactive, centered over the timeline).

- Design-system: `OpenCodeSyncingPill` + gallery on iOS and Android
- Session Chat: remove in-chat Syncing row; show top overlay while
  `isSyncingMessages` / `syncingIndicatorLabel` is set
- Working / Compacting remain in-chat status rows
- Fix: Syncing no longer sticks after reconnect — `CacheSyncConfirmed`
  clears the pipeline cache flag; timeline latches confirmation so late
  cache-generation publishes cannot re-raise the pill; poll can confirm
  while the initial REST request is still hung

## Remaining

- [ ] Manual QA on device/simulator (cache hit reopen, fingerprint-unchanged
      confirm, stacking with Working / Scroll to end, offline→online
      reopen clears Syncing)

## Decisions

See [decisions.md](./decisions.md).
