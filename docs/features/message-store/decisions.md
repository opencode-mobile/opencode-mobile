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
  snapshot cannot clear-and-reprint the live reply. If no message events arrive
  for one poll interval while text is still marked streaming, the watchdog
  REST-polls and applies the snapshot only when REST is ahead of or more
  finalized than the local stream (OPE-177). A final full-part update
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
the full TextMate parse so the UI does not stall 3–9s on the transition. Opening a history whose markdown-candidate text is ≥4k chars first publishes a
format-first `HistoryPlainFirst` frame (structured markdown scaffold — never
raw source for detectable markdown; structure-only when TextMate is on, full
semantic when off) so the timeline becomes Ready quickly, then upgrades TextMate
colors newest-first off the critical path when enabled. Both platforms publish
at most two enrichment frames after that scaffold (`enrich-viewport` for the
open bottom, then `enrich-batch` for older history), using a shared 3-message /
12k-character viewport budget. Parse work runs on a `userInitiated` worker
batch (Android `Dispatchers.Default` equivalent) instead of MainActor + utility
priority. New live snapshots invalidate enrichment; the pipeline
generation-gates every completed preparation before it mutates caches or
publishes. On iOS, `DisplayMessagePipeline` also keeps a per-session
prepared-markdown cache across leave/reopen. A warm open with a complete cache
publishes a single `cache-hit` frame with markdown already attached (peek +
observe), matching Android's LazyColumn feel: visible cells configure once,
off-screen history configures on scroll, and the UI does not wipe then reprint
rows. `MessageSync` skips an identical fingerprint republish after replaying
`SharedLiveObservation.lastValue`, and Session Chat can warm-start from
in-memory store rows without a loading spinner. On iOS, markdown parse must
stay `nonisolated`: the target sets `SWIFT_DEFAULT_ACTOR_ISOLATION =
MainActor`, so an unmarked parser would hop TextMate back onto the main thread
even inside `Task.detached`. Session Chat timelines coalesce-latest while apply
is in flight (iOS uses a tracked, lifecycle-cancelled apply task with
`Task.yield`; Android preserves explicit/post-send applies in a FIFO queue
while coalescing only callback-free live frames, and `.conflate()` on the
display observation keeps the latest fully styled enrichment snapshot when
apply is busy). Streaming text-lane coalesce always attaches a formatted
scaffold when markdown is detected (OPE-92); when TextMate is on, streaming
semantic decoration is skipped and colors arrive on Final. A residual ≥8k
streaming decoration size gate may still defer disposable color catch-up until
Final when TextMate is off — it must not leave detectable markdown as
unprocessed plain source. Android also constructs large finalized annotated
strings off the UI thread. See [plan.md](./plan.md#where-markdown-runs) and
the [Streaming fast lane](#streaming-fast-lane) decision for the full data
flow.

`RenderedMarkdownMapper` in the UI layer is removed.

## Timeline region is the display-to-row adapter

`SessionChatTimeline` is the presentation seam between prepared domain
snapshots and Session Chat row models. It owns sorting, structural block
projection, optimistic-row reconciliation, streaming markdown reuse, and
loading/empty/ready/error transitions.

The top-level `SessionChatViewModel` only composes the timeline with composer,
session-info, review, and command regions into one immutable
`SessionChatUiState`. It does not parse markdown, map message parts, or own
viewport geometry. This keeps the row-projection policy independently testable
and prevents scroll/layout state from leaking into the Screen Model.

Live presentation applies may coalesce to the newest complete snapshot while
projection is busy. Explicit load, refresh, and post-send applies are required
and remain ordered so their completion cannot be lost.

See
[message-display-rendering-pipeline.md](./message-display-rendering-pipeline.md).

## Off-main structural projection and pipeline orchestration (OPE-135)

iOS structural conversion (`DisplayFrameBuilder`, `SessionChatTimelineBlocks`,
`SessionChatTimelineProjector`) is `nonisolated` and runs inside
`Task.detached` from `SessionChatTimeline`, then publishes the immutable
`TimelineFrame` on MainActor — matching Android’s worker-dispatcher projection.

`DisplayMessagePipeline` orchestration and `PreparedHistoryCache` are also off
MainActor (lock-guarded cache). MainActor remains for Screen Model publish and
native host apply only.

## Nonisolated SSE broadcaster + filter-before-hop (OPE-140 Stage 2)

Triage chose option B (Android SharedFlow parity): `ForegroundEventStream`
publish is nonisolated. MessageSync and LiveStore observation loops consume
off-main, filter by event type, and hop to MainActor only for UI-owned work.
Product UX (permission prompts, status chrome, todos, home refresh semantics
and debounce policy) is unchanged.

See
[ios-concurrency-freeze-remediation-plan.md](./ios-concurrency-freeze-remediation-plan.md).

## Views own native rendering and scroll state

Session Chat views select role and structured-block components from immutable
rows. `OpenCodeRenderedMarkdown` materializes an already prepared document into
native text/layout objects; it never invokes the parser. A prepared document is
attached only when a row contains one text block because the document
represents the message's combined text.

Timeline pinned state, scroll-to-end visibility, content-growth anchoring, and
native paint deferral are view-owned. Android uses a reverse-layout
`LazyColumn`; iOS uses a diffable `UICollectionView` with SwiftUI-hosted cells.
Large native attributed text may paint plain-first and enrich locally without a
new pipeline or row publish. These platform-native mechanisms share the same
ordering, interaction, and auto-follow contract.

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
| `finalPlaceholder` | Reuse last streaming document when identity and source text match; marks `awaitingTextMateUpgrade` | Yes |
| `final` | Authoritative TextMate markdown; must not settle-reuse an `awaitingTextMateUpgrade` placeholder | Yes |

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

## Message display rendering hardening — Phase 1 recovery

Locked before Phase 1 implementation of
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).

### Sync health states

| State | Meaning |
| --- | --- |
| `healthy` | Last authoritative replace validated; staging accepts events |
| `recovering(reason)` | Authority lost or incomplete; last good frame retained; REST reconcile required |
| `stale(reason)` | Transport unavailable while a last good frame exists; no apply until restored |

Reasons used in Phase 1: `overflow`, `reconnect`, `reloadFailed`, `racedAuthority`,
`transportUnavailable`.

### Race rule (no server sequence IDs)

Any **session-relevant** event accepted during an authoritative REST window keeps
recovery open:

- matching `sessionID` message events (`message.*`, `session.idle`); and
- connection events that invalidate authority (`stream.reconnected`,
  `server.connected`).

Those events are **not applied** while recovering. They set a durable
`racedAuthority` requirement and schedule another revision-aware reconcile.
Recovery reports `healthy` only after a successful replace whose authority
window closed with no relevant race.

### Failure vs stale

| Condition | State |
| --- | --- |
| Overflow, raced reload, or reload failure | `recovering` with bounded backoff retry |
| Foreground transport unavailable and a last good frame exists | `stale(transportUnavailable)` |
| Transport restored from stale | enter `recovering(reconnect)` and reconcile |

Bounded backoff (provisional): start `250 ms`, multiply by `2` each failure,
cap at `5_000 ms`. Idle / watchdog / explicit refresh remain additional
convergence opportunities.

### Last-good frame

Recoverable recovery and silent reload failure **must not** clear published
timeline rows. Emit `LoadFailed` / clear the timeline only when there has never
been a successful frame for that observation (cold initial failure).

### Session key ownership

Observation cache, recovery state, fingerprint, pending-work, and store slots
owned by MessageSync use:

`connectionProfileID + directory + sessionID`

`connectionProfileID` comes from the active connection provider (default
`"local"` in tests). Display-layer caches already using the full key stay
aligned.

### Recovery visibility (Phase 1)

Health is owned inside MessageSync and assertable in the recovery/fault matrix.
`DisplayFrame.syncHealth` publication to the UI is deferred until the later
display-frame phases; Phase 1 must not depend on Screen Model changes.

## Message display rendering hardening — Phase 2 ingress

Locked before Phase 2 implementation.

### Typed message intake

`MessageEventRouter` stages only message-sync intake types before per-session
buffers:

`message.updated`, `message.part.updated`, `message.part.delta`,
`message.removed`, `message.part.removed`, `session.idle`,
`stream.reconnected`, `server.connected`.

Permission, question, home/session-list, and other global consumers keep their
own filters on the shared foreground stream. MessageSync must not amplify those
events into session staging.

### Staging bounds

| Rule | Choice |
| --- | --- |
| Count capacity | `4_096` events (unchanged) |
| Byte capacity | `2_000_000` estimated UTF-8 bytes |
| Byte estimate | `max(64, type + sessionID + delta/text field UTF-8 lengths)` |
| Overflow | Enter `recovering(overflow)`; never silently drop |
| FIFO structure | Deque / ring (no array-head `removeFirst` on the hot path) |

### Connection epoch

A monotonic `connectionEpoch` bumps when the foreground stream starts a new
connect/reconnect cycle. Each MessageSync observation captures the epoch at
subscribe time. Work stamped with a prior epoch cannot publish frames or mutate
observation health for the active observation.

### Transport stale

After the observation has seen a connected stream and holds a last-good
fingerprint, a drop to `disconnected`, `reconnecting`, or `idle` sets health to
`stale(transportUnavailable)`. A later `stream.reconnected` / `server.connected`
enters `recovering(reconnect)`. Do not overwrite an active `recovering` state
with stale.

### Executor confinement

| Platform | Ingress / reduction | UI publication |
| --- | --- | --- |
| Android | `LiveStoreScope` / `Dispatchers.Default` (already); keep Main off the hot path | Immutable `MessageListUpdate` to collectors |
| iOS | MessageSync + store + staging leave `MainActor`; serial isolation via locks/actors | Immutable frames hop to MainActor only at UI boundaries |

## Message display rendering hardening — Phase 3 reducer

Locked before Phase 3 implementation.

### Migration shape

Evolve `InMemoryMessageStore.apply` / `replaceAll` into a change-set-returning
reducer first. Introduce `SessionMessageEngine` as the named single-writer
facade over that reducer once the contract is stable. Do not leave a permanent
wrapper that keeps Sync + Store + Pipeline as three parallel orchestrators.

Repository-facing observation still publishes immutable `[Message]` /
`MessageListUpdate` for Phase 3. `DisplayFrame.changeSet` lands with later
display-frame phases.

### Apply result contract

| Result | Meaning |
| --- | --- |
| `NoChange` | Patch ignored or identical; session revision unchanged |
| `Changed(SessionChangeSet)` | Mutation committed; revision advanced once |

`SessionChangeSet` fields (invalidation description only):

`sessionRevision`, `insertedMessageIDs`, `changedMessageIDs`,
`removedMessageIDs`, `changedPartIDsByMessage`, `streamingStateChangedIDs`,
`structuralChange`.

### Live publish path

On SSE patch apply, MessageSync publishes when the result is `Changed` and the
change set is live-publishable:

- publish when `changedPartIDsByMessage`, `removedMessageIDs`, or
  `streamingStateChangedIDs` is non-empty;
- suppress info-only / empty-slot inserts that carry no part, removal, or
  streaming signal.

Use `lastPublishedRevision` plus a cached published `[Message]` frame. Splice
only change-set message IDs into that frame on dense deltas; full
`messages()` rebuild is reserved for structural inserts/removals and authority
reloads. Do not whole-history fingerprint on the live SSE path to decide
publication. Fingerprints remain the poll / REST authority comparison until
those paths move to revision gates.

### SessionMessageEngine facade

`SessionMessageEngine` is the named single-writer reducer facade
(`apply` / `replaceAll` / `messages` / `revision` / live frame splice).
MessageSync keeps transport, staging, recovery, poll, and observation.
Absorbing Sync/Pipeline into the engine is deferred to later phases with
`DisplayFrame`.

### Structural sharing depth (Phase 3)

Phase 3 requires incremental apply plus publish-boundary local reconstruction
(splice changed message IDs into the last published frame). Deep structural
sharing into display/timeline/`DisplayFrame.changeSet` is deferred.

### Streaming chunk accumulation

Part text deltas accumulate in a chunk buffer (language-native string pieces)
instead of eager `existing + delta` full copies on every tiny SSE event.
Flatten to a single string at publication / part-finalization boundaries.
Property-test: flatten ≡ sequential append for the same chunk sequence.

## Message display rendering hardening — Phase 4 lanes

Locked before Phase 4 implementation of
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).

Phase 4 splits **text** and **decoration** scheduling inside
`DisplayMessagePipeline` (and the sync/pipeline seam). It builds on existing
16/48 coalesce, generations, `MessagePreparationMode`, plain-first + two-batch
enrichment, and Phase 3 `SessionChangeSet` inside MessageSync.

### MUST vs DEFER

| MUST in Phase 4 | DEFER |
| --- | --- |
| Independent text vs decoration scheduling | `DisplayFrame` / `DisplayFrame.changeSet` / `syncHealth` UI (Phase 5+) |
| Stale decoration reject at commit | Real `blockID`, per-block markdown, interleaved decoration (Phase 5) |
| Blocked preparer cannot delay or erase plain text | Host-fed viewport from Screen Model / views (Phase 6) |
| Bound pending decoration (count + char cost) | Absorbing Sync/Pipeline into `SessionMessageEngine` |
| Optional live change-set hint into the pipeline (**never wired**; correctness uses full-list fallback — see below / Phase 7b) | Replacing `DisplayMessageListUpdate` / `DisplayMessage` publish shape |
| Meet locked streaming cadence budgets (16/48) | Changing iOS warm prepared-history cache policy |

### Publish shape

Keep the public/display observation contract on
`DisplayMessageListUpdate` / `DisplayMessage`. Dual-lane scheduling is an
**internal** pipeline concern only. Do not introduce `DisplayFrame` in Phase 4.

### Interim decoration identity (no real blockID yet)

Phase 5 owns real `blockID` + per-block `contentRevision`. Until then:

| Use | Key / gate |
| --- | --- |
| Pending decoration / commit reject | `sessionKey` + `messageID` + `textHash` + `textLength` + `isStreaming` + `generation` |
| Preparer LRU document cache | Extend current key with `sessionKey`; keep `messageID` + `textHash` + `textLength` + `isStreaming` |

`generation` gates stale completion **before** any prepared-cache mutation or
`DisplayMessageListUpdate.Messages` publish. Do **not** put `generation` in the
preparer LRU document key: same content across generations must still hit the
cache. Real `blockID` remains deferred.

### SessionChangeSet → display

Phase 4 listed an **optional** internal hint beside the immutable `[Message]`
list on the live SSE path (extend the sync → pipeline seam to prioritize or
skip unchanged rows for decoration). That hint was **never wired**. Do not
treat it as implemented. Correctness uses full-list preparation when no hint
exists; optional wiring (if any) is Phase **7b**, not 7a closeout.

| Path | Behavior |
| --- | --- |
| Live SSE `Changed` | MessageSync uses `SessionChangeSet` for **publish / splice** decisions inside Sync; it does **not** pass a decoration hint into `DisplayMessagePipeline` |
| Poll / REST / authority reload | Full-list fallback; no change-set required |
| Optional pipeline hint | **Unwired** through 7a; missing hint must not be documented as live |

Do **not** wait for `DisplayFrame.changeSet`. Repository-facing publish stays
list-shaped.

### Viewport priority

Keep the existing heuristic; do not add Screen Model or host viewport geometry
to the pipeline in Phase 4.

| Priority | Rule |
| --- | --- |
| Highest | Streaming prepare / finalization (always ahead of history enrichment) |
| Viewport enrich | Newest-first `3` messages / `12_000` characters (existing constants) |
| Remaining | Second enrich batch for older history |

### Decoration backlog bounds

Pending decoration is a separate work queue from the preparer LRU and from the
iOS warm prepared-history cache (Phase 5 decision unchanged).

| Rule | Choice |
| --- | --- |
| Pending count capacity | `128` decoration jobs |
| Pending char-cost budget | `512_000` characters (sum of candidate text lengths queued) |
| On pressure | Drop lowest-priority pending work first (remaining history → viewport enrich → never drop text-lane publish) |
| Preparer LRU | Keep `32` entries |
| iOS warm cache | Unchanged (`512_000` chars / `8` sessions LRU) |

Numbers are provisional constants tunable from high-water logs; the contract
(count + cost, drop lowest priority, plain never blocked) must not change
without revisiting this decision.

### Streaming fast lane

| Lane | Streaming policy |
| --- | --- |
| Text (correctness) | **Always** publish at the existing 16 ms quiet / 48 ms max coalesce cadence. When markdown is detected, publish a formatted scaffold (`MarkdownDocument`) — never leave detectable markdown as unprocessed plain source (OPE-92 format-always). History open uses the same rule via format-first `HistoryPlainFirst` (structure/semantic), then newest-first TextMate enrichment when enabled. |
| Decoration (disposable) | TextMate **off**: may prepare semantic catch-up; completions remain generation-gated and may be dropped. TextMate **on**: skipped while streaming — colors arrive on Final TextMate only. |
| Color later | Structure/chrome first; token colors are a separate step (semantic when TextMate off, Final/history enrichment TextMate when on). |

A blocked, slow, failed, or overloaded preparer must not delay the text lane or
erase already published formatted text. Stale decoration never mutates cache or
visible state.

## Message display rendering hardening — Phase 5 blocks + projector

Locked before Phase 5 implementation of
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).

Phase 5 depends on Phase 4 dual-lane scheduling and the interim
message-scoped decoration key. It owns **stable presentation `blockID`s**,
**per-text-block Markdown**, **`contentRevision` / `layoutRevision` on the
projector contract**, and **pure `DisplayFrame` → `TimelineFrame` projectors**
(with optimistic inputs). It does **not** rewrite native hosts to
changed-ID apply or remove list-wide layout scans — those remain Phase 6.

### MUST vs DEFER

| MUST in Phase 5 | DEFER |
| --- | --- |
| Stable `blockID` on every presentation block (direct part, merged text, tool group) | Public replace of `DisplayMessageListUpdate` / `DisplayMessage` with `DisplayFrame` |
| Per-text-block Markdown; remove `singleTextBlock` view gate | Host consumption of `layoutChangedRowIDs` / removal of Android `layoutSignature` and iOS list-wide Equatable scans |
| Pure projector API: internal `DisplayFrame` → `TimelineFrame` (+ optimistic inputs) | Moving scroll / viewport geometry into Screen Model |
| Emit per-row `contentRevision` and `layoutRevision`; frame-level changed/layout ID sets for hosts to adopt later | `DisplayFrame.syncHealth` UI on Screen Model / views |
| Decoration identity keyed by `blockID` + `contentRevision` (replace Phase 4 message-scoped interim for new work) | Absorbing Sync/Pipeline into `SessionMessageEngine` |
| Shared gate fixtures for interleaved blocks, grouping, optimistic, reconnect, plain→decorated | Deep host scroll-transaction rewrite (Phase 6) |

### Locked choices

#### Merged adjacent-text `blockID` — ordered join of all merged part IDs

Use a deterministic ordered join/segment of every server part ID that contributed
to the merged text block (stable delimiter; same order as source parts).
First-ID-only would keep identity when a later merged part appears or changes;
a synthetic index is reserved for true missing-ID handling below. Membership
change ⇒ new `blockID` (expected invalidation).

#### Collapsed tool-group `blockID` — hash/join of ordered member part IDs

Group identity is derived from ordered member part IDs. When membership
changes, the group `blockID` changes; fixtures must assert that transition
explicitly (no silent reuse of the old group identity).

#### Missing/null part ID fallback — REST must seed IDs or force recovery

Do not invent positional or content-hash presentation IDs for parts lacking a
stable server ID. Align with the store: incomplete REST rows without stable part
IDs stay on the authoritative-recovery / full-reload path. Phase 5 projectors
assume every displayable part already carries a stable ID.

#### Per-block markdown home — `DisplayFrame` text blocks; stop docs on row root

Markdown lives on decorated text blocks inside `DisplayFrame` (keyed by
`blockID` + `contentRevision`). Stop treating `DisplayMessage.renderedMarkdown`
(or equivalent row-root document) as the home for new work. Message-scoped docs
are not extended with a `blockID → document` map on `DisplayMessage`.

#### `DisplayFrame` / `TimelineFrame` timing — internal projector types; keep public list update

Introduce `DisplayFrame` and `TimelineFrame` as the **internal** projector seam
and test surface. Repository-facing observation stays
`DisplayMessageListUpdate` / `DisplayMessage` until Phase 6/7. Timeline shell
adapts projector output into today’s Screen Model row models so hosts are not
forced to rewrite in the same change.

#### `contentRevision` source — text hash + length

Per text block: `contentRevision` is derived from that block’s plain source
(`textHash` + `textLength`), continuing the Phase 4 content-addressed gate.
Do **not** key decoration to store `sessionRevision` or pipeline `generation`
(generation remains the stale-completion reject only; keep it out of the
preparer LRU key).

#### Optimistic merge location — pure projector API with explicit optimistic inputs

Move optimistic reconciliation into the pure projector as explicit inputs
(unresolved optimistic rows + authoritative frame). `SessionChatTimeline` still
owns lifecycle, observation binding, and supplying those inputs; do not push
merge policy into the Screen Model.

#### `layoutRevision` in Phase 5 — MUST emit per-row even if hosts still scan

Every height-affecting row change advances a cheap per-row `layoutRevision`.
`TimelineFrame` also carries `layoutChangedRowIDs` (and inserted/changed/removed
sets). Hosts may keep scanning in Phase 5; Phase 6 switches them to declared IDs.

#### iOS row object reuse — both until Phase 6

Keep return-previous structural sharing for unchanged rows **and** compute
declared changed IDs / value-equality checks in the projector. Phase 6 drops
dependence on list-wide Equatable scans once hosts apply declared IDs only.

### Decoration / `contentRevision` keying

Replaces the Phase 4 interim message-scoped key for **new** decoration work once
`blockID` exists. Legacy message-scoped paths may remain only as temporary
bridges during migration and must not be extended.

| Use | Key / gate |
| --- | --- |
| Pending decoration / commit reject | `sessionKey` + `blockID` + `contentRevision` + `isStreaming` + `generation` |
| Preparer LRU document cache | `sessionKey` + `blockID` + `contentRevision` + `isStreaming` (no `generation`) |
| `contentRevision` | Hash + length of that text block’s plain source |
| Non-text blocks | No Markdown decoration key |

`generation` still rejects stale completions **before** cache mutation or
publish. Same block content across generations must still hit the LRU.

### Publish / projector seam contract

| Surface | Phase 5 contract |
| --- | --- |
| Public / repository observation | Still `DisplayMessageListUpdate` / `DisplayMessage` |
| Internal display handoff | `DisplayFrame`: session key, frame/source revisions, messages with per-block plain (+ optional Markdown on text blocks). **`syncHealth` not required on the UI-facing path** |
| Pure projector | `(DisplayFrame, optimisticInputs) → TimelineFrame` |
| `TimelineFrame` | `frameRevision`, rows/blocks with `blockID`, per-row `contentRevision` / `layoutRevision`, `insertedRowIDs` / `changedRowIDs` / `removedRowIDs` / `layoutChangedRowIDs` |
| Timeline region | Calls projector; adapts to existing Screen Model row models; owns lifecycle + optimistic input supply |
| Views | Paint Markdown from the **text block** when present; **`singleTextBlock` gate MUST be removed** in Phase 5 |
| `syncHealth` UI | **Still deferred** (MessageSync-owned; no Screen Model / `DisplayFrame` UI publication in Phase 5) |

### Phase gate checklist

- Shared fixtures cover: text → tool → text; text → attachment → text; tool
  grouping; hidden tools; optimistic reconciliation; reconnect; plain → decorated.
- Stable `blockID`s survive enrichment and streaming; tool-group / merge
  membership changes produce documented new IDs.
- Interleaved text blocks can each carry Markdown; views no longer require a
  single text block to paint a document.
- Unchanged rows retain platform-appropriate identity (iOS return-previous +
  declared changed IDs; Android equivalent structural sharing).
- Projector emits `contentRevision` / `layoutRevision` and changed/layout ID
  sets even while hosts still scan.
- Public observation shape remains list-shaped; `syncHealth` is not shown in UI.

## Message display rendering hardening — Phase 6 UI frames + scroll

Locked before Phase 6 implementation of
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).

Phase 6 depends on Phase 5’s internal `DisplayFrame` → `TimelineFrame` projectors
and declared row ID sets. It owns **Screen Model reuse of `TimelineFrame`**,
**host apply of declared IDs**, **removal of live-path list-wide scans**, and
**view-owned scroll transactions**. It does **not** flip public observation to
`DisplayFrame`, surface `syncHealth` in UI, absorb Sync/Pipeline into the
engine, or run Phase 7 soak/delete. Scroll geometry stays out of the Screen
Model (prior lock: “Views own native rendering and scroll state”).

### MUST vs DEFER

| MUST in Phase 6 | DEFER |
| --- | --- |
| Put `timelineFrame` on UiState; reuse the current frame when composer / session-info / review / command republish | Public replace of `DisplayMessageListUpdate` / `DisplayMessage` with `DisplayFrame` (Phase 7) |
| Hosts apply `inserted` / `changed` / `removed` / `layoutChanged` directly; stop Android `layoutSignature` and iOS list-wide Equatable on the live path | `DisplayFrame.syncHealth` UI on Screen Model / views |
| View-owned scroll transactions: armed exact-end after layout; unarmed visible-anchor ID+offset capture/restore; drag/deceleration wins | Absorbing Sync/Pipeline into `SessionMessageEngine` |
| Preserve required load / refresh / post-send completions; coalesce only disposable live/decorative work | Forcing Android drag-apply coalesce to match iOS (keep intentional parity-gap) |
| Paint deferral stays renderer-local and revision-gated | Projector membership unification of inserts vs `layoutChanged` (hosts use union) |
| Gate host/scroll fixtures below | Long soak, delete superseded scans/compat paths, architecture-doc flip to “implemented” (Phase 7) |

`syncHealth` UI does **not** enter Phase 6 (still MessageSync-owned; assertable in
recovery suites only).

### Locked choices

| # | Question | Lock | Rationale |
| --- | --- | --- | --- |
| Q1 | `TimelineFrame` into Screen Model | **(A)** add `timelineFrame` to UiState | Enables reuse when non-timeline regions republish and gives hosts declared IDs without a new apply channel or scroll geometry in the model. |
| Q2 | Android `layoutSignature` replacement | **(A)** `layoutChangedRowIDs` (+ inserts) | Matches Phase 5 projector output; removes history-wide signature work. |
| Q3 | iOS stop list-wide Equatable | **(A)** pass declared IDs into host | Hosts apply projector sets only; Diffable insert/remove/reconfigure is host mechanism, not a second discovery pass. |
| Q4 | Unarmed visible-anchor restore | **(A)** MUST capture ID+offset on both platforms | Plan gate + performance contract; view-owned only — not Screen Model geometry. |
| Q5 | Scroll transaction ownership | **(B)** keep separate apply + auto-follow effects fed by declared IDs | Armed auto-follow already works; do not invent a unified `apply(frame, scrollIntent)` Screen-facing API. Hosts sequence apply → follow/restore locally. |
| Q6 | Android drag coalesce | **(B)** keep intentional parity-gap | Documented in `parity-gaps.md`; Compose newest-immutable is fine. Revisit only if soak shows product divergence (Phase 7). |
| Q7 | Public `DisplayFrame` | **(A)** stay internal through Phase 6 | Phase 5 lock; public observation flip is Phase 7 closeout, not required for host ID apply. |
| Q8 | Insert vs `layoutChanged` parity | **(C)** hosts treat `inserted ∪ layoutChanged` as layout-affecting | Ships without forcing projector membership rewrite; both sets already exist. |

#### Paint deferral — confirmed renderer-local

Native plain-first → styled paint stays inside the renderer / cell, revision-gated
by `blockID` + `contentRevision` / `layoutRevision`. It must not republish rows
or feed scroll geometry back into the Screen Model.

### Screen Model / host contract

| Surface | Phase 6 contract |
| --- | --- |
| Public / repository observation | Still `DisplayMessageListUpdate` / `DisplayMessage` |
| Internal display handoff | `DisplayFrame` remains internal (no public observation flip) |
| Pure projector | Unchanged: `(DisplayFrame, optimisticInputs) → TimelineFrame` |
| Screen Model / UiState | Holds `timelineFrame` (rows + `frameRevision` + declared ID sets). Reuse the same frame object/value when only composer / session-info / review / command change |
| Native host apply | Consume declared IDs only — no live-path `layoutSignature` or list-wide Equatable scan |
| Layout-affecting set | `insertedRowIDs ∪ layoutChangedRowIDs` (plus any host-local height observation after apply) |
| Content-only set | `changedRowIDs` minus layout-affecting, as needed for cell reconfigure without scroll restore |
| Scroll / pinned / follow | View-owned; never fields on UiState |
| `syncHealth` UI | Still deferred |
| Paint deferral | Renderer-local, revision-gated |

### Scroll transaction rules

Host-local sequencing after each accepted frame apply (not a Screen Model API):

1. **Armed follow** — apply declared row changes → await layout → settle at exact end.
2. **Unarmed** — capture visible anchor row ID + offset → apply → restore ID+offset within native rounding.
3. **Dragging / decelerating** — user input wins; optional decoration/live applies may coalesce to the latest frame. iOS keeps cell-apply coalesce; Android keeps newest-immutable (intentional parity-gap).
4. **Required completions** — load / refresh / post-send stays ordered and lossless; only disposable live/decorative work coalesces.
5. **Declared IDs feed both** apply and auto-follow effects; do not rediscover diffs by scanning all rows.

### Phase gate checklist

- Screen Model reuses the current `TimelineFrame` when non-timeline UiState fields change (no O(n) remap solely from composer/session-info/review).
- Live path: no Android history-wide `layoutSignature`; no iOS list-wide Equatable discovery.
- Hosts apply only declared `inserted` / `changed` / `removed` / `layoutChanged` IDs.
- Unarmed: both platforms capture/restore visible anchor ID+offset through append and plain→decorated height change.
- Armed: append and height growth settle at exact end after layout.
- Active drag: user wins; required completion callbacks remain ordered/lossless.
- Paint deferral remains renderer-local (no row republish for local style enrichment).
- `syncHealth` is not shown in UI; public observation stays list-shaped.
- Gate-required host/scroll fixtures: append; height growth; keyboard/viewport shrink; scroll-away (unarmed); scroll-to-end (re-arm); active drag; plain → decorated height change; first plain paint before decoration; changed-ID-only application.
- UI executor frame-application work meets the locked budget (no parsing / highlighting / full-history projection on apply).

## Message display rendering hardening — Phase 7 soak + closeout

Locked before Phase 7 implementation of
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).

Phase 7 closes this hardening plan as an honest **7a soak + documentation +
dead-path hygiene** gate under the architecture Phases 1–6 actually shipped.
Mega-migrations deferred from Phases 4–6 (public `DisplayFrame`, Sync/Pipeline
absorption, fingerprint retirement, `syncHealth` UI) move to a follow-up
**7b** ticket and are **not** required to mark this plan complete. Scroll stays
view-owned; Screen Model does not gain viewport geometry.

### MUST vs DEFER

| MUST in Phase 7a (this plan closes) | DEFER to Phase 7b / later ticket |
| --- | --- |
| Named soak matrix green on both platforms (unit/integration acceptable) | Public flip of observation to `DisplayFrame` (+ optional `syncHealth` / `changeSet` on that shape) |
| Delete unused / superseded dead bridges and probe markers that never fire | Absorbing `MessageSync` + `DisplayMessagePipeline` into `SessionMessageEngine` |
| Flip architecture docs from target / stale Phase-5 language to **implemented** | Replacing poll/REST fingerprints with revision gates |
| Drop unwired optional `SessionChangeSet`→pipeline hint from “implemented” claims | Wiring that hint as an optimization |
| Keep intentional mechanism gaps recorded in `parity-gaps.md` | Forcing Android drag coalesce to match iOS |
| Align plan + HTML + `progress.md` + this decisions lock | Device soak CI / hard p95 gate; `syncHealth` banner/chip UI |
| Confirm recovery/fault + characterization suites still cover reliability invariants under soak names | Moving scroll / viewport into Screen Model (explicitly out of scope forever for this plan) |

### Locked choices

| # | Question | Lock | Rationale |
| --- | --- | --- | --- |
| Q1 | Public `DisplayFrame` observation | **(B)** keep list-shaped; document intentional | Phases 4–6 already ship internal `DisplayFrame` + `TimelineFrame` on UiState. Flipping the repository observation shape is a consumer migration, not soak hygiene. |
| Q2 | `syncHealth` UI | **(B)** keep assert-only on `MessageSync` | Health is already assertable in the fault matrix. Banner/chip is product surface, not required to close reliability. |
| Q3 | Engine absorption | **(B)** DEFER; facade remains | Engine is the named reducer facade by Phase 3 lock. Sync + Pipeline stay separate orchestrators through 7a; absorption is 7b. |
| Q4 | Message-root `renderedMarkdown` | **(B)** keep sole-text bridge | Phase 5 closed with sole-text bridge + per-block `BlockDecorationCache`. Deleting the bridge is not soak closeout. |
| Q5 | Poll fingerprints | **(C)** keep until soak proves cost | Live SSE already uses change-set / revision rules. Poll/REST fingerprints are intentional Phase 3 retainers; replace only if soak shows measurable cost. |
| Q6 | Android drag coalesce | **(B)** keep gap unless soak fails | Phase 6 intentional parity-gap (Compose newest-immutable). Promote only if soak shows product-visible divergence. |
| Q7 | Phase 7 definition | **(C)** split 7a closeout / 7b absorption | Finishes this plan without stuffing engine merge + public `DisplayFrame` into the same gate. 7b owns deferred architecture. |

### Probe marker hygiene

| Marker | Action |
| --- | --- |
| Emitted markers already used on recovery / reducer / display publish paths | **Keep** |
| `TimelineFrameCommitted` | **Delete** (never emitted; do not force host wiring in 7a) |
| `FirstVisiblePlainPaint` | **Delete** (never emitted; first-plain coverage stays in host/characterization tests, not probe enum dead weight) |
| New host paint / timeline-commit instrumentation | **DEFER** to 7b if a future soak CI needs wall-clock paint spans |

### `SessionChangeSet` → pipeline hint

**Drop from implemented claims.** Phase 4 marked it optional; it was never wired.
Do **not** implement in 7a. Correct docs/closeout text that imply the hint is
live. Optional later optimization may land in 7b; correctness does not depend on
it (poll/REST full-list fallback remains).

### Delete / keep inventory (summary)

**Delete / stop claiming in 7a**

- Unused probe markers `TimelineFrameCommitted`, `FirstVisiblePlainPaint`
- Any residual live-path dependence on Android `layoutSignature` or iOS
  list-wide Equatable discovery (should already be gone; verify + remove dead
  helpers/tests)
- Doc language that still describes sole-text Markdown gate or history-wide
  layout signature as current behavior
- Claims that Sync/Pipeline are absorbed, that public observation is
  `DisplayFrame`, or that the optional change-set→pipeline hint is implemented

**Keep (intentional through 7a)**

- Public `DisplayMessageListUpdate` / `DisplayMessage`
- Internal `DisplayFrame`; `TimelineFrame` on UiState
- `DisplayMessage.renderedMarkdown` sole-text bridge + `BlockDecorationCache`
  per-block decoration
- Poll/REST fingerprints; live SSE change-set publish path
- `SessionMessageEngine` as thin reducer facade; Sync + Pipeline as separate
  orchestrators
- `syncHealth` assert-only inside `MessageSync`
- Android drag newest-immutable vs iOS cell coalesce (parity-gap)

### Soak gate checklist

Shared scenario names (both platforms; same observable outcomes):

1. Long-history open (format-first scaffold before TextMate color enrichment)
2. Dense stream (bounded progressive plain frames)
3. Reconnect / authority restore
4. Repeated overflow → recovering → healthy only after validated replace
5. Parser / preparer failure (plain retained)
6. Memory / backlog pressure (decoration bounds; staging overflow recovery)
7. Rapid session switch / teardown (no cross-session publish)
8. Active scroll (user wins; unarmed anchor; armed exact-end)

**Evidence standard for 7a:** unit + characterization + `MessageSync` fault
matrix under those shared names is **gate-sufficient**. No dedicated soak CI
exists; do not invent one as a Phase 7a blocker.

**Not gate-required in 7a:** device p95 (≤50 ms plain / ≤4 ms apply) as hard CI.
Retain as reference-device / best-effort notes if measured; failure to automate
p95 does not block 7a.

**Pass rule:** both platforms green on the named matrix; no product-visible
divergence unless recorded in `parity-gaps.md`.

### Doc closeout list (MUST flip to “implemented”)

| File | Required flip |
| --- | --- |
| `message-display-rendering-pipeline.md` | Highest lag: remove sole-text gate / `layoutSignature` as current; describe declared-ID hosts, internal `DisplayFrame` → `TimelineFrame`, sole-text bridge as residual |
| `assistant-message-pipeline.md` | Reflect dual-lane prep, block decoration, recovery health ownership, list-shaped public observe |
| `message-display-rendering-hardening-plan.md` | Phase 7 → Complete via 7a; record 7b deferrals; acceptance criteria checkboxes match reality |
| `progress.md` | Frontier = plan closed (7a); 7b called out if tracked |
| `decisions.md` | This Phase 7 lock |
| `parity-gaps.md` | Confirm Android drag newest-immutable + any soak-found intentional gaps |
| `plan.md` | Only where it still points prepare → message-root `renderedMarkdown` as the sole decoration home |

### Phase gate checklist

- Shared soak scenario names pass on Android and iOS via unit/characterization +
  MessageSync fault coverage
- Unused probe markers deleted; no dead emit stubs left behind
- No live-path `layoutSignature` / list-wide Equatable discovery; dead
  helpers/tests removed if still present
- Public observation remains list-shaped; documented as intentional (not
  “temporary until Phase 7”)
- `syncHealth` remains assert-only; no Screen Model / banner requirement
- `SessionMessageEngine` remains reducer facade; Sync + Pipeline absorption
  explicitly parked in 7b
- Sole-text `renderedMarkdown` bridge kept; views continue to prefer per-block
  Markdown
- Poll/REST fingerprints retained unless soak proves cost (then open 7b work,
  do not block 7a)
- Android drag coalesce gap kept unless soak shows product divergence
- Optional `SessionChangeSet`→pipeline hint removed from implemented claims
  (not wired in 7a)
- Doc ledger above flipped to implemented language; plan/HTML/progress/
  decisions/parity agree
- Phase 7b plan locked (coverage-first; architecture parked with reopen
  triggers): [message-display-rendering-7b-plan.md](./message-display-rendering-7b-plan.md)

## Message display rendering hardening — Phase 7b follow-ups

Locked after Phase 7a closeout of
[message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).
Canonical plan:
[message-display-rendering-7b-plan.md](./message-display-rendering-7b-plan.md).
Linear: [OPE-86](https://linear.app/opencode-mobile/issue/OPE-86/message-display-rendering-phase-7b)
· [OPE-87](https://linear.app/opencode-mobile/issue/OPE-87/fill-phase-7a-soak-coverage-gaps).

Phase 7b is an **umbrella + risk-sliced** follow-up, not a mega-migration gate.
Ship soak **coverage debt** only. Park architecture items without a product,
correctness, or cost trigger. Scroll stays view-owned.

### Locked choices

| # | Question | Lock |
| --- | --- | --- |
| Q1 | Public `DisplayFrame` location | **(B)** keep list-shaped forever |
| Q2 | Engine absorption DoD | **(C)** intentional 3-layer indefinitely (Engine facade + Sync + Pipeline) |
| Q3 | Fingerprint trigger | **(C)** keep / won’t-do for 7b |
| Q4 | Changeset hint transport | **(C)** skip for 7b |
| Q5 | `syncHealth` UI | **(C)** stay assert-only / out of 7b |
| Q6 | p95 / soak CI | **(C)** out of 7b |
| Q7 | Ticket shape | **(B)** umbrella plan + risk-sliced tickets |

### MUST / SHOULD / DEFER / OUT

| MUST | SHOULD | DEFER (new triage only) | OUT of 7b |
| --- | --- | --- | --- |
| Docs lock + list-shaped / 3-layer / view-owned scroll preserved | Keep soak matrix + parity-gaps current with coverage | Public `DisplayFrame` flip; Sync/Pipeline absorption; fingerprint→revision; change-set hint; `syncHealth` UI; sole-text bridge deletion; device p95 CI — each only if its reopen trigger fires | Public observation flip; absorption; fingerprint work; change-set hint; `syncHealth` UI; p95 CI; sole-text bridge deletion; Screen Model scroll; Android drag-coalesce force; one epic of all six |

### Slice order

1. **Coverage ticket (ship):** [OPE-87](https://linear.app/opencode-mobile/issue/OPE-87/fill-phase-7a-soak-coverage-gaps) — 7a soak partials → full (repeated overflow, parser failure, rapid switch; related pressure gaps where cheap). Exit: matrix scenarios 4–7 full on both platforms; no API shape change.
2. **Architecture reopen:** not scheduled under 7b.

### Coverage vs architecture

Phase 7a soak partials are a **separate coverage ticket** under this umbrella —
not architecture work and not unfinished Phase 5/6.

### Sole-text bridge

`DisplayMessage.renderedMarkdown` sole-text bridge stays. **Out of 7b.** Delete
only when zero production readers remain.

### Reopen triggers (create new triage; do not reopen options here)

- Public `DisplayFrame`: second consumer needs frame metadata list updates cannot carry.
- Absorption: split ownership causes a shipped bug or blocks a required single test surface.
- Fingerprints: measurable poll/REST fingerprint cost.
- Change-set hint: measurable over-broad live decoration invalidation.
- `syncHealth` UI: product asks for visible recovering/stale/healthy.
- Sole-text bridge deletion: no remaining message-root markdown readers.
- p95 CI: release process requires automated responsiveness gates.

## OPE-136 — Diffable apply packet must stay declared under status chrome

**Decision:** Session Chat always passes `MessageListTimelineApplyPacket.from(timelineFrame)` into `MessageListTimelineHost`, including while Working status indicator rows are appended. Status chrome is structural via item IDs; clearing the packet is not allowed. Syncing is a top overlay pill (OPE-137), not a timeline row.

**Nil-packet fallback:** Identity-stable nil-packet applies update `itemsByID` only and must not synthesize `reconfigureAllSurviving` with an auto-bumped `frameRevision`. Gallery mutations pass an explicit `listRevision` packet instead.

**Status chrome:** Fold Working labels into apply `frameRevision` + `changedRowIDs` so label-only republishes reconfigure that stable row without clearing the declared packet.

**Why:** Live freeze samples showed MainActor stuck in Diffable `apply` inside SwiftUI `updateUIViewController`. With a nil packet, every update bumped revision and reconfigured all surviving rows in a layout→update loop (≈99% CPU / multi-GB RSS).
