# Message Store — Parity Gaps

Android synchronizes its mutable in-memory store and iOS confines store
mutations to `MainActor`; these are platform-specific concurrency mechanisms
for the same atomic merge and revision-check contract, not a product behavior
difference.

## Warm reopen prepared-markdown cache (intentional)

iOS `DisplayMessagePipeline` keeps a prepared-markdown map across leave/reopen,
keyed by connection profile + directory + session, bounded by a `512_000`-char
budget and `8` sessions (LRU), and opens with a single `cache-hit` / warm peek
when complete. Android resets `previousById` per observation and still runs
format-first scaffold + newest-first enrichment on reopen; LazyColumn only
recomposes changed visible rows, so that path already feels responsive. Product
UX should match (instant usable chat, no hang, no row-by-row wipe); the caching
mechanism may stay iOS-only until Android needs it.

The temporary iOS performance gap in final native attributed-string
construction belongs to the renderer and is tracked in
[Native Rendered Markdown parity gaps](../native-rendered-markdown/parity-gaps.md#final-native-annotation-construction).

## Timeline host and native paint mechanisms (intentional)

The message display contract is shared, while the host and scheduling
mechanisms remain platform-native:

| Area | Android | iOS |
| --- | --- | --- |
| Timeline host | Reverse-layout `LazyColumn` with a bottom anchor at layout index `0` | Diffable `UICollectionView` with SwiftUI-hosted, self-sizing cells |
| Structural projection | Worker dispatcher, then `StateFlow` publish | MainActor projection with cooperative yields |
| Declared-ID apply | Hosts consume `inserted` / `changed` / `removed` / `layoutChanged` from `TimelineFrame` (no live-path `layoutSignature`) | Same declared sets; no list-wide Equatable discovery on the live path |
| Apply while user scrolls / drags | Compose newest-immutable: visible items recompose from the latest frame (no cell-apply coalesce queue) | Host coalesces pending cell applies and flushes the latest after drag/deceleration |
| Large markdown paint | Large code/diff annotations build off Main; streaming large fences stay plain | Same 2k code/diff behavior, plus a one-frame/32 ms whole-bubble markdown defer at 3k characters |

Both platforms must preserve stable message identity, block ordering, user
scroll priority, exact-end auto-follow while armed, and format-first scaffold
replacement without duplicating or reordering a row. The iOS whole-bubble defer
is an intentional `UICollectionView` cell-host protection, not a product-level
loading state or a second display-pipeline publish. Android drag
newest-immutable vs iOS cell-apply coalesce is an intentional mechanism gap
(Phase 6 / 7a); revisit only if soak shows product-visible divergence.

See
[message-display-rendering-pipeline.md](./message-display-rendering-pipeline.md).

## List-shaped public observation (intentional)

Repository-facing observation stays `DisplayMessageListUpdate` /
`DisplayMessage`. Internal `DisplayFrame` → `TimelineFrame` (with
`timelineFrame` on UiState) is the projector/Screen Model seam. Public
`DisplayFrame` observation is **won’t-do** under Phase 7b (keep list-shaped).
Reopen only via a new triage if a second consumer needs frame metadata list
updates cannot carry. See
[message-display-rendering-7b-plan.md](./message-display-rendering-7b-plan.md).

## SessionMessageEngine facade (intentional)

`SessionMessageEngine` is the named single-writer reducer facade over store
apply/replace. `MessageSync` and `DisplayMessagePipeline` remain separate
orchestrators — intentional 3-layer. Absorption is **out of 7b**; reopen only
if split ownership causes a shipped bug or blocks a required single test
surface.

## Poll / REST fingerprints (intentional)

Live SSE publish uses change-set / revision rules and spliced frames.
Poll and REST authority paths still compare fingerprints. Retirement is
**won’t-do for 7b** until a profiler/soak shows measurable poll/REST fingerprint
cost.

## Sole-text `renderedMarkdown` bridge (intentional)

Per-block Markdown is the decoration home (`BlockDecorationCache`, keyed by
`blockID` + `contentRevision`). `DisplayMessage.renderedMarkdown` remains only
as a residual sole-text bridge for callers that still read message-root docs.
Deletion is **out of 7b**; reopen when zero production readers remain.

## `syncHealth` assert-only (intentional)

`MessageSyncHealth` is owned inside `MessageSync` and assertable in the fault
matrix. No Screen Model banner/chip or public-frame `syncHealth` UI in 7a or
7b. Reopen only if product asks for visible recovering/stale/healthy.

## History enrichment publication strategy

Resolved in Phase 4 of [pipeline-hardening-plan.md](./pipeline-hardening-plan.md).
Both platforms publish at most one newest-viewport enrichment frame (3 messages
/ 12k character budget) and one remaining-history frame after the format-first
scaffold. Newest-first preparation is preserved inside each batch; generations
gate both publishes. Android timeline `.conflate()` keeps the latest fully
styled snapshot when apply is busy.

## Prepared-work cancellation semantics

Resolved in Phase 2 of [pipeline-hardening-plan.md](./pipeline-hardening-plan.md).
Both platforms now generation-gate cache mutation and publication; intake no
longer awaits in-flight preparation; observation teardown drops pending work
instead of flushing. Remaining enrichment publication-count differences were
resolved under History enrichment publication strategy (Phase 4).
