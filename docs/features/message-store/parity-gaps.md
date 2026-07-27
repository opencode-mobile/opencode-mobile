# Message Store — Parity Gaps

Android synchronizes its mutable in-memory store and iOS confines store
mutations to `MainActor`; these are platform-specific concurrency mechanisms
for the same atomic merge and revision-check contract, not a product behavior
difference.

## Warm reopen prepared-markdown cache (intentional)

iOS `DisplayMessagePipeline` keeps a prepared-markdown map across leave/reopen,
keyed by connection profile + directory + session, bounded by a `512_000`-char
budget and `8` sessions (LRU), and opens with a single `cache-hit` / warm peek
when complete. Android resets `previousById` per observation and still
plain-first + enrich on reopen; LazyColumn only recomposes changed visible
rows, so that path already feels responsive. Product UX should match (instant
usable chat, no hang, no row-by-row wipe); the caching mechanism may stay
iOS-only until Android needs it.

The temporary iOS performance gap in final native attributed-string
construction belongs to the renderer and is tracked in
[Native Rendered Markdown parity gaps](../native-rendered-markdown/parity-gaps.md#final-native-annotation-construction).

## History enrichment publication strategy

Resolved in Phase 4 of [pipeline-hardening-plan.md](./pipeline-hardening-plan.md).
Both platforms publish at most one newest-viewport enrichment frame (3 messages
/ 12k character budget) and one remaining-history frame after plain-first.
Newest-first preparation is preserved inside each batch; generations gate both
publishes. Android timeline `.conflate()` keeps the latest fully styled
snapshot when apply is busy.

## Prepared-work cancellation semantics

Resolved in Phase 2 of [pipeline-hardening-plan.md](./pipeline-hardening-plan.md).
Both platforms now generation-gate cache mutation and publication; intake no
longer awaits in-flight preparation; observation teardown drops pending work
instead of flushing. Remaining enrichment publication-count differences were
resolved under History enrichment publication strategy (Phase 4).
