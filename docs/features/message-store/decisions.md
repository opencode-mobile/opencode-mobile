# Message Store — Decisions

Still binding:

- Session Chat reads `observeDisplayMessages` / `refreshDisplayMessages`. Send
  stays on the same repository.
- Store is in-memory (`InMemoryMessageStore`). No local DB until we add one.
- `MessageSync` owns REST + SSE merge. Incremental `message.*` patches stay.
  Live shape tracks TUI v2 ([server-api-v2](../server-api-v2/decisions.md)):
  hydrate / merge on reconnect, patch while the stream is live, do not
  REST-reload the transcript on `session.idle` when the last assistant
  already has visible content. If that last assistant has no visible
  content (empty/hidden reasoning, dropped `text.ended`), REST-reload.
  Treat `sse.parse.failed` as an SSE gap and REST-reload. Poll REST when
  SSE is not live, streaming is stalled, or cache sync is still awaiting
  confirm. Skip a UI emit when reconnect merge leaves the fingerprint
  unchanged unless cache confirm is still pending. Revision-gate in-flight
  REST so it cannot overwrite a newer SSE revision.
- v2 `session.tool.*` events after `input.started` are sparse. TUI keeps
  name on its tools map (`current.part.name ?? "tool"` is display only).
  Do not invent `"tool"` as part identity. Parse nameless tool updates.
  Store overlay: a real name is never replaced by blank / `"tool"`.
  Live `message.part.updated` must overlay provided fields onto the
  existing part (TUI tools map), not replace it. Blank name / empty
  `input` / empty text do not wipe what the store already has, and do
  not drop live text/reasoning deltas. After input merge, re-resolve the
  name from the merged input (`query` → `websearch`, `filePath` → `read`)
  so later nameless `session.tool.*` events still surface Explored rows.
- Reasoning is a distinct part. Never apply a reasoning delta onto `TextPart`.
- Markdown is prepared in `DisplayMessagePipeline`, not in UI or the raw store.
  Live streaming tokens coalesce into display frames (80ms open window, 160ms
  max age) so the timeline paints word-sized chunks instead of every SSE
  delta. Session Chat Appearance **Smooth Streaming** (default on) gates this;
  off paints every streaming snapshot immediately. Stream end and structural
  snapshots still flush immediately. Do not animate row height while streaming
  (GrowBottom).
- Views own native paint and scroll. Screen Model does not own viewport
  geometry.
- SSE transport (foreground-only, 45s silence watchdog) is
  [foreground-event-stream](../foreground-event-stream/decisions.md).
  Return from background, path restore, and pull-to-refresh catch-up are
  [reactive-data-layer](../reactive-data-layer/decisions.md): MessageSync
  stale bit plus interest, not a `ForegroundRestore` UI registry.
  `stream.reconnected` still runs MessageSync catch-up for warm drops and
  cold resume after stop, coalesced with world-signal REST.
