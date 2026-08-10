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
