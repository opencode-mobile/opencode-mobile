# Message Store — Decisions

Still binding:

- Session Chat reads `observeDisplayMessages` / `refreshDisplayMessages`. Send
  stays on the same repository.
- Store is in-memory (`InMemoryMessageStore`). No local DB until we add one.
- `MessageSync` owns REST + SSE merge. Incremental `message.*` patches stay.
  REST is authoritative on idle / reconnect. Revision-gate in-flight REST so
  it cannot overwrite a newer SSE revision.
- Reasoning is a distinct part. Never apply a reasoning delta onto `TextPart`.
- Markdown is prepared in `DisplayMessagePipeline`, not in UI or the raw store.
  16/48 coalesce is for markdown decoration, not for hiding the raw stream.
- Views own native paint and scroll. Screen Model does not own viewport
  geometry.
- SSE only while foregrounded; 45s idle timeout; reconnect refreshes.
