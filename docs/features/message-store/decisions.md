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
- Reasoning is a distinct part. Never apply a reasoning delta onto `TextPart`.
- Markdown is prepared in `DisplayMessagePipeline`, not in UI or the raw store.
  16/48 coalesce is for markdown decoration, not for hiding the raw stream.
- Views own native paint and scroll. Screen Model does not own viewport
  geometry.
- SSE transport (foreground-only, 45s silence watchdog) is
  [foreground-event-stream](../foreground-event-stream/decisions.md).
