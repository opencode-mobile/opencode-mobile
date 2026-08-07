# Session Compact — Decisions

## Richer compact UX is status chrome only (prototype B)

- **Date:** 2026-08-07
- **Decision:** Upgrade Session Compact feedback using prototype variant **B**
  (Compacting… status chrome + context-delta snackbar). Do not ship timeline
  checkpoint cards (A) or float pill + summary sheet (C).
- **Reason:** Compaction is a rare one-shot action. B reuses Working / Stop /
  context ring / snackbar. C competes with the todos float stack; A adds a
  timeline surface for a model-oriented checkpoint users rarely need to read.
- **Revisit when:** Users ask to read the checkpoint summary, or the server
  returns summarize before compaction finishes and needs event-driven progress.

## One verb — compact, not summarize

- **Date:** 2026-08-07
- **Decision:** Keep a single Commands action `[compact]`. Do not add Summarize.
  OpenCode treats `/summarize` as an alias of `/compact`.
- **Reason:** OPE-79/OPE-80 investigation; avoid duplicate products for one API.
- **Revisit when:** Product defines a human-facing digest distinct from context
  compaction.

## Success copy prefers measurable context delta

- **Date:** 2026-08-07
- **Decision:** On success, prefer
  `Context compacted · {before}% → {after}%` when both context usage fractions
  are known; otherwise keep `Session compacted.`
- **Reason:** Delta proves the action worked; fallback preserves today’s
  behavior when tokens/limits are missing.
- **Revisit when:** Context usage moves to a dedicated store with clearer before
  snapshots.

## Clear Compacting before post-success refresh

- **Date:** 2026-08-07
- **Decision:** Clear `isCompacting` as soon as summarize succeeds (or fails).
  Derive the after context fraction from the SSE-updated timeline + a light
  status refresh. Do not await a full message reload before clearing chrome.
  Run summarize off the UI thread / MainActor.
- **Reason:** On iOS, awaiting `refreshDisplayMessages` + MainActor timeline
  rebuild after compact froze Session Chat with Compacting… stuck until
  relaunch. SSE already streams the compacted transcript.
- **Revisit when:** After-fraction must come from a dedicated usage store that
  is not ready until an explicit refresh completes.

## Compacting… includes remote observers

- **Date:** 2026-08-07
- **Decision:** Effective Compacting… is local `isCompacting` **or**
  server-detected active compaction: session busy and the active turn (from the
  last user message through the end of the turn) contains a kept `compaction`
  part or an assistant with `mode`/`agent` equal to `"compaction"`
  (case-insensitive). Keep compaction parts in the domain model for presence;
  do not render them in the timeline.
  Do not rely on `session.time.compacting`. Success snackbar stays initiator-only.
- **Reason:** A second client with the same session open never set the local
  Commands flag, so it only showed generic Working. Live message + busy signals
  are what opencode already emits for a compact turn.
- **Revisit when:** Server exposes a dedicated compaction status field or V2
  `session.compaction.*` that is authoritative for all clients.
