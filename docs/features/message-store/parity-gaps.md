# Message Store — Parity Gaps

Android synchronizes its mutable in-memory store and iOS confines store
mutations to `MainActor`; these are platform-specific concurrency mechanisms
for the same atomic merge and revision-check contract, not a product behavior
difference.

## Warm reopen prepared-markdown cache (intentional)

iOS `DisplayMessagePipeline` keeps a per-session prepared-markdown map across
leave/reopen and opens with a single `cache-hit` / warm peek when complete.
Android resets `previousById` per observation and still plain-first + enrich on
reopen; LazyColumn only recomposes changed visible rows, so that path already
feels responsive. Product UX should match (instant usable chat, no hang, no
row-by-row wipe); the caching mechanism may stay iOS-only until Android needs
it.

The temporary iOS performance gap in final native attributed-string
construction belongs to the renderer and is tracked in
[Native Rendered Markdown parity gaps](../native-rendered-markdown/parity-gaps.md#final-native-annotation-construction).
