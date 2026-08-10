# Session Cache Sync Indicator — Decisions

## Top overlay Syncing pill (OPE-137)

- **Decision:** Replace the in-chat **Syncing…** status row with a
  non-interactive floating pill centered over the **top** of the Session Chat
  timeline, using the same floating chrome family as Scroll to end.
- **Reason:** The in-chat row felt like another message and was annoying during
  quiet cache→network confirm. A top twin of the scroll-to-end pill keeps the
  signal visible without shifting timeline content.
- **Prototype:** Locked Variant A (mirror pill) from
  `docs/prototypes/syncing-top-pill/` (rejected veil chip, wide progress bar,
  and trailing badge).
- **Stacking:** Working / Compacting stay as in-chat end rows. Syncing clears on
  network confirmation independently of turn indicators.

## Syncing must clear after reconnect

- **Decision:** Treat `CacheSyncConfirmed` as authority over the Syncing flag:
  clear `sourceIsFromCache` in DisplayMessagePipeline; latch confirmation in
  Session Chat timeline so late cache-generation publishes cannot re-raise the
  pill; allow the poll loop to emit `CacheSyncConfirmed` when the initial REST
  request is still hung after the network returns.
- **Reason:** Opening a cached session offline then reconnecting left Syncing
  stuck — confirmation could be coalesced away, enrichment republished
  `isFromCache=true`, and a hung initial `listMessages` blocked confirm.
