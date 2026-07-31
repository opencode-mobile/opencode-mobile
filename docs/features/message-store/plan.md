# Message Store

## User Goal

Session Chat reads messages through one repository API backed by an in-memory
store that REST history and SSE events keep up to date. Markdown parse and
tokenize run in a content-preparation layer so UI receives almost-ready rows.

## In Scope

- `MessageStore` / `InMemoryMessageStore` — per-session message state and SSE patches.
- `MessageSync` — event subscription before initial load, incremental SSE merge,
  selective reconciliation, reconnect reload, and revision-gated fingerprint
  polling.
- `MessageContentPreparer` + `DisplayMessagePipeline` — parse, highlight, cache → `DisplayMessage`.
- `MessageRepository.observeDisplayMessages` / `refreshDisplayMessages` / `prepareOptimisticUserMessage`.
- Session Chat timeline depends on display-message APIs only (no UI-layer markdown mapper).

## Out Of Scope

- SQLite / Room / SwiftData persistence.
- Paginated history (`limit` / `before` / `x-next-cursor`).
- Per-message `getMessage` reconcile on every SSE tick.
- Markdown in the raw message store.

## Server APIs Involved

- `GET /session/:id/message` — seed and reload history.
- `GET /global/event` (via `ForegroundEventStream`) — ordered live patches,
  connection events, and reconnect signals.

## Expected Logs

- `MessageSync` records subscription, authoritative reload reason, SSE-derived
  publishes, changed-fingerprint polls, and recoverable reload failures without
  logging message text.
- `MessageContentPreparer` records preparation counts, cache reuse, markdown
  counts, and elapsed time without logging message text.
- The foreground stream owns connection lifecycle and parsed-event logging; the
  store does not duplicate transport logs.

## Domain Objects

- `Message`, `MessagePatch`, `MessageListUpdate`, `MessageStore`, `MessageSync`, `MessageRepository`.
- `DisplayMessage`, `DisplayMessageListUpdate`, `MessageContentPreparer`, `DisplayMessagePipeline`.

## Architecture

Domain messages (`Message` with plain text/parts) flow from the store through
sync into a **content-preparation pipeline** that emits list-shaped
`DisplayMessage` rows. Per-text-block Markdown lives in `BlockDecorationCache`
(keyed by `blockID` + `contentRevision`); `DisplayMessage.renderedMarkdown` is
only a residual sole-text bridge. Session Chat projects internal `DisplayFrame`
→ `TimelineFrame` and views paint per-block documents.

```mermaid
flowchart TB
  subgraph ui [Session Chat UI — presentation]
    VM[SessionChatViewModel]
    Timeline[SessionChatTimeline]
    Composer[SessionChatComposer]
    Rows[SessionChatMessageItem / SessionChatRow]
    VM --> Timeline
    VM --> Composer
    Timeline --> Rows
    Composer --> Rows
  end

  subgraph prepare [Domain — content preparation]
    Pipeline[DisplayMessagePipeline]
    Preparer[MessageContentPreparer]
    Display[DisplayMessage]
    Pipeline --> Preparer
    Preparer --> Display
  end

  subgraph domain [Domain — message data]
    Repo[MessageRepository]
    Sync[MessageSync]
    Store[InMemoryMessageStore]
    Msg[Message plain text and parts]
    Repo --> Sync
    Sync --> Store
    Store --> Msg
  end

  subgraph infra [Infrastructure]
    API[MessagesApi]
    Events[ForegroundEventStream]
  end

  Timeline -->|observeDisplayMessages refreshDisplayMessages| Repo
  Composer -->|prepareOptimisticUserMessage sendPromptAsync| Repo
  Repo --> Pipeline
  Pipeline --> Sync
  Sync --> API
  Sync --> Events
  Repo --> API
```

### Where markdown runs

Full load vs part-streaming flow, ordering guarantees, and execution contexts
are documented in [assistant-message-pipeline.md](./assistant-message-pipeline.md).
The implemented ViewModel-to-native-pixels architecture, including row
projection, structured blocks, native markdown painting, and timeline scroll
ownership, is documented in
[message-display-rendering-pipeline.md](./message-display-rendering-pipeline.md).
A self-contained visual review is available in
[message-display-rendering-pipeline.html](./message-display-rendering-pipeline.html).
The latest-wins, bounded cross-platform hardening is complete in
[pipeline-hardening-plan.md](./pipeline-hardening-plan.md), with a step-status
board in [pipeline-hardening-plan.html](./pipeline-hardening-plan.html).
The next reliability and responsiveness slice is planned in
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md),
with a visual phase board in
[message-display-rendering-hardening-plan.html](./message-display-rendering-hardening-plan.html).

| Step | Layer | What happens |
|------|--------|----------------|
| REST + SSE | `MessageSync` → `SessionMessageEngine` / store | Raw server messages kept as domain `Message`; engine is reducer facade |
| Observe | `DisplayMessagePipeline` | Dual-lane text + decoration; calls preparer |
| **Prepare** | **`MessageContentPreparer`** | Detect markdown, parse, highlight → per-block docs in `BlockDecorationCache` (sole-text `DisplayMessage.renderedMarkdown` residual bridge only) |
| Structural merge | Pure projector + `SessionChatTimeline` | `DisplayFrame` → `TimelineFrame`; optimistic inputs; declared ID sets |
| Screen state | `SessionChatViewModel` | `timelineFrame` on UiState; combine composer, info, review, commands |
| Render | Session Chat views / hosts | Declared-ID apply; paint Markdown from text blocks |

Composer uses `prepareOptimisticUserMessage` for the **optimistic send bubble**
before the server row appears. Streaming text updates re-prepare on each
display pipeline emit (debounced while parts are streaming).

Observation registers for foreground events before requesting initial history,
so events that arrive during the REST request are buffered and merged after the
snapshot. REST responses replace the store only when its revision still matches
the revision captured before the request; an intervening SSE patch makes that
response stale and it is discarded.

REST mapping retains stable part identities so selective updates do not consume
the event loop with a reload or lose subsequent deltas. Legacy parts without a
server ID use the safe full-reload fallback. A newer live snapshot must never
wait for stale history enrichment to finish.

**Not in scope for the store:** tokenizer, parser, or highlighter — those live
in `MessageContentPreparer` (backed by KMP markdown on Android and the native
bridge on iOS).

## Screen Model State And Actions

- Session Chat receives loading, ready, empty, and load-failed outcomes through
  the existing timeline state.
- Explicit refresh uses `refreshDisplayMessages`; send uses
  `prepareOptimisticUserMessage` followed by the repository send API.
- Live and watchdog updates are quiet timeline updates and must not clear an
  in-flight optimistic bubble or `isSending`.

## Navigation Entry Points

- Android: `SessionChatTimeline` observes the repository while the scoped
  Session Chat ViewModel is alive.
- iOS: `SessionChatTimeline` starts observation on appearance and stops it on
  the existing delayed-disappear/back lifecycle.

## Error States

- Initial history failure produces a load-failed update.
- Explicit refresh failure is returned to the caller.
- SSE or reconciliation failure keeps the last good snapshot.
- If authoritative `session.idle` reconciliation fails, streaming flags are
  cleared and the watchdog poll resumes so a later REST response can recover.

## Permission Behavior

Not applicable; permission requests use their own foreground-event subscriber.

## Design-System Components

No new component. Session Chat continues to use
`OpenCodeRenderedMarkdown`; the display pipeline supplies its prepared document
and streaming state.

## Validation

- Android: `MessageSyncTest`, `DisplayMessagePipelinePacingTest`,
  `MessageContentPreparerTest`, and `SessionChatViewModelTest`.
- iOS: `MessageSyncTests`, `DisplayMessagePipelinePacingTests`, and
  `SessionChatViewModelTests`.
- Both message-sync suites cover events buffered during initial REST, stale
  in-flight poll rejection, failed-idle recovery, and
  event → store → display streaming/final behavior.
