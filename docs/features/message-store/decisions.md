# Message Store — Decisions

## Single read API

Session Chat observes `MessageRepository.observeDisplayMessages` for timeline
rows and `refreshDisplayMessages` for explicit reloads. Send stays on the same
repository. `MessageLiveStore` is removed.

## Store is in-memory for v1

`InMemoryMessageStore` holds REST snapshots plus SSE-derived info/parts. No local
database until offline scope is revisited.

## Sync owns transport merge policy

`MessageSync` subscribes to `ForegroundEventStream` and calls `MessagesApi` for
reloads. The event stream does not call the repository directly.

## Incremental SSE merge stays

Text deltas append locally; tool parts replace by ID; base-only rows fall back to
full reload. Matches opencode web behavior and prior `MessageLiveStore` semantics.

## Markdown in content preparation, not UI or store

`MessageContentPreparer` parses and tokenizes markdown inside the domain layer.
`DisplayMessagePipeline` wraps `MessageSync.observeMessages`, debounces streaming
updates, and emits `DisplayMessageListUpdate`. Session Chat timeline performs
structural merge only (order, optimistic rows, quiet refresh). See
[plan.md](./plan.md#where-markdown-runs) for the full data flow.

`RenderedMarkdownMapper` in the UI layer is removed.

## Logging

`MessageSync` tag replaces `MessageLiveStore` in log filters (`MessageSync` on
Android; same subsystem patterns on iOS). Content preparation logs under
`MessageContentPreparer` on Android.
