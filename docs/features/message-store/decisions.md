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
  reload as the fallback finalization path. If that reload fails, the store
  still clears its streaming flags so watchdog polling resumes and can recover
  the authoritative result later.
- Observation starts buffering foreground events before the initial REST load.
  This closes the startup window where a non-replayed SSE delta could otherwise
  arrive while history is loading and be lost.
- Every initial, explicit, event-driven, and watchdog REST replacement is
  revision-gated. If an SSE mutation lands while the request is in flight, the
  stale response is discarded rather than overwriting newer incremental state.
- `replaceAll` always clears streaming flags, then seeds the incremental cache
  from stable part IDs retained by REST domain messages. Later part updates and
  deltas can therefore replace the targeted part while preserving its siblings.
  A legacy REST row containing a part without a stable ID still falls back to a
  full reload rather than replacing an incomplete subset of the row's parts.
- Android serializes store reads and mutations per store instance so nested
  message/part collections are never read while another coroutine mutates them.

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
then enriches markdown newest-first off the critical path. Both platforms
publish at most twice after plain-first (`enrich-viewport` for the open bottom,
then `enrich-batch` for older history), using a shared 3-message / 12k-character
viewport budget. Parse work runs on a
`userInitiated` worker batch (Android `Dispatchers.Default` equivalent) instead
of MainActor + utility priority. New live snapshots invalidate enrichment;
the pipeline-hardening target adds a generation check before any completed
preparation mutates caches or publishes. On iOS, `DisplayMessagePipeline` also keeps a
per-session prepared-markdown cache across leave/reopen. A warm open with a
complete cache publishes a single `cache-hit` frame with markdown already
attached (peek + observe), matching Android's LazyColumn feel: visible cells
configure once, off-screen history configures on scroll, and the UI does not
wipe then reprint rows. `MessageSync` skips an identical fingerprint republish after
replaying `SharedLiveObservation.lastValue`, and Session Chat can warm-start
from in-memory store rows without a loading spinner. On iOS, markdown
parse must stay `nonisolated`: the target sets `SWIFT_DEFAULT_ACTOR_ISOLATION =
MainActor`, so an unmarked parser would hop TextMate back onto the main thread
even inside `Task.detached`. Session Chat timelines coalesce-latest
while apply is in flight (iOS uses a tracked, lifecycle-cancelled apply task
with `Task.yield`; Android preserves explicit/post-send applies in a FIFO queue
while coalescing only callback-free live frames, and `.conflate()` on the
display observation keeps the latest fully styled enrichment snapshot when apply
is busy). Large streaming replies (≥8k
chars) skip markdown preparation and render plain text until the message
finishes; large streaming code fences (≥2k chars) also render as plain monospace
until highlight runs on the final frame. Android also constructs large
finalized annotated strings off the UI thread. See
[plan.md](./plan.md#where-markdown-runs) for the full data flow.

`RenderedMarkdownMapper` in the UI layer is removed.

## Pipeline hardening — latest-wins generations (Phase 2)

A finalized non-streaming snapshot owns one observation-local generation.
Placeholder reuse and the authoritative final prepare both commit only while
that generation is still current. Newer live snapshots increment the
generation and discard in-flight finalization work.

Cancellation remains advisory for synchronous TextMate work. Generation checks
gate every prepared-cache mutation and every `DisplayMessageListUpdate.Messages`
publish. Observation teardown cancels and drops pending work; it does not flush
a deferred streaming snapshot.

## Pipeline hardening — preparation modes (Phase 3)

Preparation uses one closed platform-native mode. Boolean flag combinations are
not part of the public preparer/pipeline call shape.

| Mode | Purpose | Updates prepared cache |
| --- | --- | --- |
| `streaming` | Live streaming prepare; plain text above the streaming size threshold | Yes |
| `historyPlainFirst` | Usable cold history without missing markdown parses | No full replace; merge only rows that already carry a document |
| `historyEnrichment` | Newest-first missing markdown for history | Yes (per enriched row / batch) |
| `finalPlaceholder` | Reuse last streaming document when identity and source text match | Yes |
| `final` | Authoritative finalized markdown, including cache-hit and small history | Yes |

Parse failure always falls back to safe plain text for the current generation and
must not throw into the timeline. Streaming text above the configured threshold
uses plain text under `streaming` until finalization runs `final`.

## Pipeline hardening — bounds and overflow (Phase 5)

Phase 5 locks capacities and recovery before implementation. Numbers are
provisional constants that may be tuned from high-water logs; the recovery
contract must not change without revisiting this decision.

### iOS warm prepared-history cache

Android keeps the intentional no-warm-cache parity gap unless later measurement
shows reopen hangs without one.

| Rule | Choice |
| --- | --- |
| Key | `connectionProfileID` + `directory` + `sessionID` (no cross-profile or cross-directory reuse) |
| Cost unit | Sum of retained `DisplayMessage.text` character counts across all cache entries |
| Total cost budget | `512_000` characters |
| Session entry cap | `8` retained sessions (whichever of cost or entry count binds first) |
| Eviction order | Least-recently used session entry until under both limits |
| Also evict on | Session delete, profile/connection reset (`clearPreparedCache`), memory-pressure notification |
| Effect of eviction | Performance only (cold reopen may plain-first + enrich); raw store correctness unchanged |

Character cost is the measurable proxy for parse/render weight. Rendered-document
heap size is not tracked directly; memory-pressure eviction covers underestimation.

### Event intake (both platforms)

Applies to the **per-observation staging buffer** between foreground event
subscription and store apply (Android today: `MessageSync` `.buffer(Channel.UNLIMITED)`;
iOS equivalent startup/live staging). The Android fan-out SharedFlow capacity
of `4_096` stays as the process-wide emit buffer and is **not** the silent-drop
path for accepted text deltas.

| Rule | Choice |
| --- | --- |
| Capacity unit | Event count (not byte cost) |
| Staging capacity | `4_096` events (aligned with existing Android SharedFlow sizing) |
| On overflow | Never drop accepted deltas silently |
| Overflow action | Mark the observation `needsAuthoritativeRecovery`, stop enqueueing further staged events for that observation, and enter authoritative recovery |
| If initial REST is not yet authoritative | Do not commit that REST snapshot as authority; recover first |
| If already live | Trigger revision-aware full reload (`overflow-recovery`); resume staging only after a successful authoritative replace |
| Observability | Log high-water mark, overflow, and recovery reason without message content |

Byte-cost accounting is deferred: event count matches the existing fan-out bound
and is cheap to enforce on the hot path.

See [pipeline-hardening-plan.md](./pipeline-hardening-plan.md) for Phase 5
implementation steps.

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
no per-delta `launch`) with a 4,096-event SharedFlow buffer so dense
`message.part.delta` bursts do not spawn thousands of emit jobs.

## Logging

`MessageSync` tag replaces `MessageLiveStore` in log filters (`MessageSync` on
Android; same subsystem patterns on iOS). Content preparation logs under
`MessageContentPreparer` on Android.
