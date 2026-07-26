# Message Store — Parity Gaps

No synchronization or store-behavior parity gaps are currently tracked for this
slice. Android synchronizes its mutable in-memory store and iOS confines store
mutations to `MainActor`; these are platform-specific concurrency mechanisms
for the same atomic merge and revision-check contract, not a product behavior
difference.

The temporary iOS performance gap in final native attributed-string
construction belongs to the renderer and is tracked in
[Native Rendered Markdown parity gaps](../native-rendered-markdown/parity-gaps.md#final-native-annotation-construction).
