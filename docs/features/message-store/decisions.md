# Message Store — Decisions

Still binding:

- Session Chat reads `observeDisplayMessages` / `refreshDisplayMessages`. Send
  stays on the same repository.
- Store is in-memory (`InMemoryMessageStore`). No local DB until we add one.
- `MessageSync` owns REST + SSE merge. Incremental `message.*` patches stay.
  Live shape tracks TUI v2 ([server-api-v2](../server-api-v2/decisions.md)):
  hydrate / merge on reconnect, patch while the stream is live, do not
  REST-reload the transcript on `session.idle`. Poll REST only when SSE is
  not live, or when streaming is stalled. Skip a UI emit when reconnect
  merge leaves the fingerprint unchanged. Revision-gate in-flight REST
  so it cannot overwrite a newer SSE revision.
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
  16/48 coalesce is for markdown decoration, not for hiding the raw stream.
- Views own native paint and scroll. Screen Model does not own viewport
  geometry.
- SSE transport (foreground-only, 45s silence watchdog) is
  [foreground-event-stream](../foreground-event-stream/decisions.md).
