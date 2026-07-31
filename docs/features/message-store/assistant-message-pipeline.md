# Assistant message pipeline architecture

How Session Chat turns server messages into timeline rows for **full load**
(history open) and **part streaming** (live assistant text).

This document describes the **implemented** cross-platform assistant-message
pipeline. Earlier pipeline-hardening acceptance lives in
[pipeline-hardening-plan.md](./pipeline-hardening-plan.md). End-to-end display
reliability Phases 1–6 are shipped; Phase 7a is soak + documentation closeout
in
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).
Feature scope and durable decisions remain canonical in [plan.md](./plan.md)
and [decisions.md](./decisions.md). Intentional platform mechanism differences
are recorded in [parity-gaps.md](./parity-gaps.md).

## Architectural goals

The pipeline must:

- preserve every accepted SSE text delta until it is merged or an explicit
  authoritative-recovery path takes over;
- reject stale REST snapshots using store revisions;
- keep snapshot intake responsive while markdown preparation is running;
- publish prepared snapshots monotonically so older work cannot overwrite newer
  messages or cache entries;
- show usable history before expensive markdown work finishes;
- keep streaming cadence responsive without parsing every token event;
- perform parsing, tokenization, and large structural transforms away from the
  UI executor;
- bound retained prepared content and buffered event work; and
- expose one display-message stream with equivalent observable behavior on iOS
  and Android.

## External seam

Session Chat depends on `MessageRepository.observeDisplayMessages` and
`refreshDisplayMessages`. The repository delegates display behavior to one deep
`DisplayMessagePipeline` module. Timeline callers do not choose debounce,
preparation, caching, or finalization strategies. Public observation stays
list-shaped (`DisplayMessageListUpdate` / `DisplayMessage`) by intentional Phase
7a lock; internal `DisplayFrame` / `TimelineFrame` live in the UI projector
seam documented in
[message-display-rendering-pipeline.md](./message-display-rendering-pipeline.md).

```mermaid
flowchart LR
  UI[SessionChatTimeline] -->|observe| Repo[MessageRepository]
  Repo -->|delegate| Pipe[DisplayMessagePipeline]
  Pipe -->|observe raw messages| Sync[MessageSync]
  Sync <--> Engine[SessionMessageEngine]
  Engine <--> Store[InMemoryMessageStore]
  Pipe -->|prepare| Prep[MessageContentPreparer]
  Pipe -->|decorate| Cache[BlockDecorationCache]
  Pipe -. DisplayMessage updates .-> Repo
  Repo -. DisplayMessage updates .-> UI
```

`MessageSync` owns transport, staging, recovery, poll, and observation.
`SessionMessageEngine` is the named single-writer reducer facade over store
apply/replace (Sync + Pipeline remain separate orchestrators through 7a).
`DisplayMessagePipeline` owns dual-lane display policy: a text lane that always
publishes plain source at the 16/48 coalesce cadence, and a disposable
decoration lane that writes per-block Markdown into `BlockDecorationCache`.
`MessageContentPreparer` owns markdown parsing and highlighting.
`SessionChatTimeline` owns observation lifecycle and supplies optimistic inputs
to the pure projector. `syncHealth` is assert-only on `MessageSync` (no UI).
The optional Phase 4 `SessionChangeSet` → pipeline decoration hint was never
wired.

## Data flow

The pipeline has two cooperating planes.

```mermaid
flowchart TB
  subgraph Merge["Transport and merge plane"]
    REST[REST listMessages]
    SSE[ForegroundEventStream]
    Sync[MessageSync]
    Store[InMemoryMessageStore]
    Raw[Authoritative Message snapshot]
    REST --> Sync
    SSE --> Sync
    Sync --> Store
    Store --> Raw
  end

  subgraph Display["Display preparation plane"]
    Intake[Latest-snapshot intake]
    Policy[Pipeline state and pacing policy]
    Worker[Preparation worker]
    Guard{Generation still current?}
    Cache[Bounded prepared cache]
    Output[DisplayMessageListUpdate]
    Intake --> Policy
    Policy --> Worker
    Worker --> Guard
    Guard -->|yes| Cache
    Cache --> Output
    Guard -->|no| Drop[Discard stale result]
  end

  Raw --> Intake
  Output --> Timeline[SessionChatTimeline]
```

Snapshot intake and preparation are separate. Intake records the newest
snapshot and advances its generation without waiting for synchronous parser
work. A completed preparation may update caches or publish only when its
generation is still current.

## Pipeline model

Each accepted raw snapshot is represented internally by:

| Field | Meaning |
| --- | --- |
| `generation` | Monotonically increasing observation-local identifier |
| `messages` | Immutable raw message snapshot |
| `receivedAt` | Monotonic time used by the pacing policy |
| `containsStreamingText` | Whether any text part is currently streaming |
| `source` | Optional diagnostic origin: initial load, SSE patch, poll, reconnect, idle, or explicit refresh |

Preparation uses a closed mode instead of independent boolean flags:

| Mode | Behavior | Updates prepared cache |
| --- | --- | --- |
| `streaming` | Prepare the latest streaming snapshot; attach format-always scaffold when markdown is detected | Yes |
| `historyPlainFirst` | Publish format-first history scaffolds (structure/semantic); defer TextMate color enrichment | No full replace; merge existing docs only |
| `historyEnrichment` | Prepare missing history markdown newest-first | Yes |
| `finalPlaceholder` | Reuse the last streaming document when source text still matches | Yes |
| `final` | Prepare authoritative finalized markdown | Yes |

The mode is internal to the pipeline. Callers receive only
`DisplayMessageListUpdate`. The cache-commit policy is locked in
[decisions.md](./decisions.md#pipeline-hardening--preparation-modes-phase-3).

## State transitions

| State | Input | Action | Next state |
| --- | --- | --- | --- |
| `loading` | Initial snapshot | Cache hit, final preparation, or format-first publish | `stable` or `enriching` |
| `loading` | Load failure | Cancel pending work and publish `loadFailed` | `failed` |
| `stable` | Streaming snapshot | Record latest generation and start pacing gate | `streaming` |
| `streaming` | New streaming snapshot | Replace pending snapshot; reschedule quiet gate | `streaming` |
| `streaming` | Streaming clears | Invalidate streaming work; publish an eligible placeholder; schedule final preparation | `finalizing` |
| `finalizing` | Newer snapshot | Discard older completed work and evaluate the newer snapshot | state implied by snapshot |
| `enriching` | Newer live snapshot | Invalidate remaining enrichment immediately | state implied by snapshot |
| any active state | Observation ends | Cancel scheduling and drop unpublished work | `terminated` |

`finalPlaceholder` is opportunistic. Timeline coalescing may replace it with the
final frame before SwiftUI or Compose renders it. The placeholder DisplayMessage
sets `awaitingTextMateUpgrade` so `final` cannot settle-reuse streaming
(semantic-fallback) spans; pipeline Final prepare also uses the pre-placeholder
snapshot as `previousByID`.

## 1. Full load

### Transport to store

Foreground events are registered before the initial REST request so early part
events are not lost. The request captures a store revision. Its response may
replace the store only if that revision is still current.

```mermaid
sequenceDiagram
  participant Events as ForegroundEventStream
  participant Sync as MessageSync
  participant REST as Messages API
  participant Store as InMemoryMessageStore
  participant Pipe as DisplayMessagePipeline

  Sync->>Events: Register recovery-aware event intake
  Sync->>Store: Capture revision
  Sync->>REST: listMessages
  Events-->>Sync: Buffer or stage ordered events
  REST-->>Sync: Message history
  Sync->>Store: replaceAllIfRevision
  Sync->>Store: Apply or retain newer event state
  Store-->>Pipe: Revisioned latest snapshot
```

Event intake must have an explicit capacity and overflow-recovery policy. It
must not silently discard text deltas. If capacity is exceeded, the sync layer
marks the session as needing authoritative recovery and prevents the stale
initial REST response from becoming authoritative.

### Display preparation

```mermaid
flowchart TD
  Snapshot[Final message snapshot] --> Cache{Complete valid prepared cache?}
  Cache -->|yes| Hit[Publish cache-hit]
  Cache -->|no| Size{Markdown candidates at least 4k chars?}
  Size -->|no| Final[Prepare and publish final]
  Size -->|yes| Plain[Publish historyPlainFirst]
  Plain --> Viewport[Prepare newest viewport batch]
  Viewport --> PublishViewport[Publish enrich-viewport]
  PublishViewport --> Remaining[Prepare remaining history]
  Remaining --> PublishBatch[Publish enrich-batch]
```

Both platforms publish at most two enrichment frames after the
format-first scaffold:

1. the newest messages needed for the initial viewport, constrained by message
   count and character budget; and
2. the remaining prepared history.

A newer live snapshot invalidates both batches. Completed stale work is
discarded by the generation guard.

## 2. Part streaming

Text deltas patch the in-memory store incrementally. Timeline polling pauses
while any text part is streaming so a lagging REST response cannot clear and
reprint the reply.

```mermaid
flowchart TD
  SSE[message.part.delta field=text] --> Store[Append delta and mark streaming]
  Store --> Snapshot[Authoritative raw snapshot]
  Snapshot --> Intake[Replace latest pending generation]
  Intake --> Gate{Pacing gate}
  Gate -->|48ms maximum age| Start[Start preparation]
  Gate -->|16ms quiet window| Start
  Start --> Worker[Prepare streaming mode on CPU worker]
  Worker --> Current{Generation still current?}
  Current -->|no| Drop[Discard]
  Current -->|yes| Scaffold[Publish format-always scaffold]
```

The 16 ms quiet window and 48 ms maximum batch age are pacing defaults, not
ordering guarantees. The generation check is the ordering guarantee. Disposable
decoration may still defer for very large streaming fences when TextMate is off;
the text lane must not leave detectable markdown as unprocessed plain source.

### Finalization

When streaming clears:

1. invalidate any pending or in-flight streaming generation;
2. reuse the last streaming document as `finalPlaceholder` only when message
   identity and source text still match;
3. schedule `final` preparation on the CPU worker;
4. publish the result only if its generation remains current; and
5. resume polling once the store no longer contains streaming text.

If `session.idle` reconciliation fails, the store clears streaming flags and
the watchdog may recover through a later revision-gated REST response.

## Ordering and cancellation guarantees

- Generations increase in raw-snapshot acceptance order.
- Only the current generation may mutate the prepared cache.
- Only the current generation may publish a prepared snapshot.
- Cancelling a task is not assumed to interrupt synchronous TextMate work.
  Cancellation prevents its result from being committed.
- Enrichment is lower priority than live streaming and finalization.
- Observation teardown drops pending work. There is no UI-visible teardown
  flush contract.
- A load failure cancels pending debounce and enrichment work before publishing
  `loadFailed`.

## Execution contexts

These are execution contexts, not guaranteed operating-system threads.

| Responsibility | iOS | Android |
| --- | --- | --- |
| Pipeline state, generation, cache decisions | `@MainActor` | Observation scope on `LiveStoreScope` / `Dispatchers.Default` |
| REST and SSE transport | `URLSession` | OkHttp / Ktor |
| Markdown parse and highlight | `Task.detached(priority: .userInitiated)` with a post-await generation check | `withContext(Dispatchers.Default)` with a post-work generation check |
| Store atomicity | `MainActor` confinement | Per-store synchronization |
| Timeline structural conversion | MainActor with cooperative yields | Worker dispatcher, then Main `StateFlow` publish |
| UI rendering | SwiftUI on MainActor | Compose on Main |

Platform mechanisms may differ, but ordering, publish-count, recovery, and
boundedness guarantees are shared.

The downstream Screen Model, row projection, native renderer, and auto-follow
architecture are documented in
[message-display-rendering-pipeline.md](./message-display-rendering-pipeline.md).

## Cache and buffer policy

- Per-block Markdown documents are keyed by `sessionKey` + `blockID` +
  `contentRevision` + streaming state in `BlockDecorationCache` / preparer LRU.
  `DisplayMessage.renderedMarkdown` is a residual sole-text bridge only.
- The iOS warm prepared-history cache is keyed by connection profile, directory,
  and session ID, and bounded by a `512_000`-character text-cost budget and an
  `8`-session entry cap (LRU eviction). Eviction also covers session deletion,
  connection/profile reset, and memory pressure.
- Android may add a warm prepared-history cache only if measurement shows it is
  needed; instant usable history remains the shared product requirement.
- Pending decoration is bounded separately (`DecorationBacklog`: count + char
  cost); pressure drops lowest-priority jobs and never blocks the text lane.
- Per-observation event staging is capped at `4_096` events and a byte budget.
  Overflow marks authoritative recovery required and never silently continues
  with missing deltas.
- Cache eviction affects performance only. It must not affect raw message
  correctness.
- Poll / REST authority paths still use fingerprints; live SSE uses change-set
  / revision publish rules (fingerprint retirement is parked under
  [Phase 7b](./message-display-rendering-7b-plan.md); won’t-do without a cost signal).

## Failure behavior

| Failure | Required behavior |
| --- | --- |
| Initial REST failure | Publish `loadFailed`; retain no pending display work |
| Explicit refresh failure | Return the error to the caller; keep last good timeline |
| Parser / preparer failure | Keep published plain text for the current generation; record failure without message content; do not throw into the timeline |
| SSE disconnect | Keep last good snapshot; `stale(transportUnavailable)` then `recovering(reconnect)` on restore |
| Idle reconciliation failure | Clear streaming flags and allow watchdog recovery |
| Event-intake overflow | Enter `recovering(overflow)`; never silently continue with missing deltas; healthy only after validated authoritative replace |

## Observability

Logs must not include message text. Production and test seams already record:

- snapshot source and generation;
- preparation mode;
- debounce or maximum-batch trigger;
- accepted versus discarded generation;
- preparation duration and character count;
- cache hit, miss, and eviction counts;
- enrichment batch number and message count;
- buffer high-water mark and overflow recovery;
- decoration backlog pressure; and
- timeline structural-apply duration.

Phase 7a maps those invariants onto the shared soak scenario names in
[phase-7a-soak-matrix.md](./phase-7a-soak-matrix.md).

## Platform differences

Observable publish ordering, preparation modes, enrichment batch counts, and
overflow recovery match across platforms. Remaining intentional differences:

- iOS retains a bounded warm prepared-history cache across leave/reopen;
  Android rebuilds prepared state per observation (LazyColumn already avoids
  row wipe).
- Store atomicity and timeline structural conversion use platform-native
  executors (`MainActor` vs synchronized store / worker→Main).
- Android drag apply is newest-immutable; iOS coalesces cell applies during
  drag (see [parity-gaps.md](./parity-gaps.md)).

See [parity-gaps.md](./parity-gaps.md).

## Source and verification map

### iOS

- [`MessageSync.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/MessageSync.swift)
- [`SessionMessageEngine.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/SessionMessageEngine.swift)
- [`DisplayMessagePipeline.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/DisplayMessagePipeline.swift)
- [`MessageContentPreparer.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/MessageContentPreparer.swift)
- [`BlockDecorationCache.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/BlockDecorationCache.swift)
- [`DecorationBacklog.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/DecorationBacklog.swift)
- [`DefaultForegroundEventStream.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Event/DefaultForegroundEventStream.swift)
- [`SessionChatTimeline.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/UI/SessionChat/Region/SessionChatTimeline.swift)
- [`MessageSyncTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/MessageSyncTests.swift)
- [`SessionMessageEngineTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/SessionMessageEngineTests.swift)
- [`DisplayMessagePipelinePacingTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/DisplayMessagePipelinePacingTests.swift)
- [`DisplayMessagePipelineWarmReopenTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/DisplayMessagePipelineWarmReopenTests.swift)
- [`DisplayMessagePipelineCharacterizationTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/DisplayMessagePipelineCharacterizationTests.swift)
- [`DecorationBacklogTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/DecorationBacklogTests.swift)
- [`BlockDecorationCacheTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/BlockDecorationCacheTests.swift)
- [`PreparedHistoryCacheTests.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20MobileTests/PreparedHistoryCacheTests.swift)
- [`MessageEventStaging.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/MessageEventStaging.swift)
- [`PreparedHistoryCache.swift`](../../../ios/OpenCode%20Mobile/OpenCode%20Mobile/Domain/Message/PreparedHistoryCache.swift)

### Android

- [`MessageSync.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/message/MessageSync.kt)
- [`SessionMessageEngine.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/message/SessionMessageEngine.kt)
- [`DisplayMessagePipeline.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/message/DisplayMessagePipeline.kt)
- [`MessageContentPreparer.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/message/MessageContentPreparer.kt)
- [`BlockDecorationCache.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/message/BlockDecorationCache.kt)
- [`DecorationBacklog.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/message/DecorationBacklog.kt)
- [`LiveStoreScope.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/live/LiveStoreScope.kt)
- [`SessionChatTimeline.kt`](../../../android/app/src/main/java/dev/opencode/mobile/ui/sessionchat/region/SessionChatTimeline.kt)
- [`LiveStoreTest.kt`](../../../android/app/src/test/java/dev/opencode/mobile/domain/live/LiveStoreTest.kt)
- [`SessionMessageEngineTest.kt`](../../../android/app/src/test/java/dev/opencode/mobile/domain/message/SessionMessageEngineTest.kt)
- [`DisplayMessagePipelinePacingTest.kt`](../../../android/app/src/test/java/dev/opencode/mobile/domain/message/DisplayMessagePipelinePacingTest.kt)
- [`DisplayMessagePipelineCharacterizationTest.kt`](../../../android/app/src/test/java/dev/opencode/mobile/domain/message/DisplayMessagePipelineCharacterizationTest.kt)
- [`DecorationBacklogTest.kt`](../../../android/app/src/test/java/dev/opencode/mobile/domain/message/DecorationBacklogTest.kt)
- [`BlockDecorationCacheTest.kt`](../../../android/app/src/test/java/dev/opencode/mobile/domain/message/BlockDecorationCacheTest.kt)
- [`MessageContentPreparerTest.kt`](../../../android/app/src/test/java/dev/opencode/mobile/domain/message/MessageContentPreparerTest.kt)
- [`MessageEventStaging.kt`](../../../android/app/src/main/java/dev/opencode/mobile/domain/message/MessageEventStaging.kt)
