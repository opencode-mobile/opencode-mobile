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

`MessageSync` merges live events into `InMemoryMessageStore`:

- `message.part.updated` upserts the full part (and clears streaming on text
  replace).
- `message.part.delta` appends text chunks by `partID` and sets
  `isStreaming: true` (OPE-82; matches current opencode server + web client).
  Only events with `field=text` are accepted; missing and non-text fields are
  ignored. Non-text part shells (e.g. `reasoning`) are also ignored so their
  text-field deltas never become assistant message text.
- Timeline polling pauses while a text part is streaming so a lagging REST
  snapshot cannot clear-and-reprint the live reply. A final full-part update
  clears streaming normally; `session.idle` performs an authoritative REST
  reload as the fallback finalization path.
- `replaceAll` always clears incremental caches and streaming flags. Part IDs
  are private to the SSE cache and are not retained in REST domain messages, so
  an update or removal targeting a REST-seeded row uses one full reload rather
  than replacing an incomplete subset of the row's parts.

Legacy `delta` on `message.part.updated` remains supported in the store for
older fixtures, but production streaming uses `message.part.delta`.

## Markdown in content preparation, not UI or store

`MessageContentPreparer` parses and tokenizes markdown inside the domain layer.
`DisplayMessagePipeline` wraps `MessageSync.observeMessages`, coalesces streaming
updates over a short quiet window (~16ms) with a flat max batch age (48ms) so
tiny SSE deltas batch into fewer UI publishes without intentionally slowing
down as the reply grows. When streaming ends, the pipeline first emits a
`final-placeholder` that reuses the last streaming markdown document, then runs
the full TextMate parse so the UI does not stall 3–9s on the transition. Opening
a history whose markdown-candidate text is ≥4k chars first publishes a
`plain-first` frame (no markdown parse) so the timeline becomes ready quickly,
then enriches **one markdown message at a time, newest first** (utility QoS /
single-thread worker, with a short yield between publishes) so the bottom of
the timeline (where open settles) paints markdown before older off-screen
history, and scrolling stays responsive instead of freezing through a
multi-second batch TextMate pass. On iOS, markdown
parse must stay `nonisolated`: the target sets `SWIFT_DEFAULT_ACTOR_ISOLATION =
MainActor`, so an unmarked parser would hop TextMate back onto the main thread
even inside `Task.detached`. Session Chat timelines coalesce-latest
while apply is in flight (iOS uses a tracked, lifecycle-cancelled apply task
with `Task.yield`; Android uses `conflate()`). Large streaming replies (≥8k
chars) skip markdown preparation and render plain text until the message
finishes; large streaming code fences (≥2k chars) also render as plain monospace
until highlight runs on the final frame. See
[plan.md](./plan.md#where-markdown-runs) for the full data flow.

`RenderedMarkdownMapper` in the UI layer is removed.

## SSE client timeouts

Android `OpencodeSseHttpClient` disables OkHttp read/write/call timeouts (and
sets Ktor socket/request timeouts infinite) so sparse heartbeats do not kill
`/global/event`. iOS already uses infinite `URLSession` request/resource
timeouts for the events client. REST clients keep normal timeouts.

## SSE framing and fan-out (Android)

Android `SseDataLineParser` matches iOS: flush each complete JSON `data:`
payload without requiring a blank event separator, ignore `:` comments, and
request `Accept-Encoding: identity` so framing stays line-oriented.
`DefaultForegroundEventStream` emits on the SSE read coroutine (ordered,
no per-delta `launch`) with a larger SharedFlow buffer so dense
`message.part.delta` bursts do not spawn thousands of emit jobs.

## Logging

`MessageSync` tag replaces `MessageLiveStore` in log filters (`MessageSync` on
Android; same subsystem patterns on iOS). Content preparation logs under
`MessageContentPreparer` on Android.
