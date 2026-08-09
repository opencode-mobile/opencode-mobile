# Message Store — Progress

## Shared planning

- [x] One repository-backed message observation contract is documented for both
  native apps.
- [x] OPE-82 `message.part.delta` streaming, reconciliation, watchdog, and
  content-preparation behavior are documented.
- [x] The latest-wins, bounded target architecture and phased cross-platform
  hardening plan are documented.
- [x] Phase 1 characterization and test seams are in place on iOS and Android
  (`DisplayMessagePipelineCharacterizationTest` /
  `DisplayMessagePipelineCharacterizationTests`).
- [x] Phase 2 latest-wins generations, intake/prepare split, and drop-on-teardown
  are implemented on both platforms.
- [x] Phase 3 closed preparation modes replace boolean preparer flags on both
  platforms (`MessagePreparationMode`).
- [x] Phase 4 shared two-batch history enrichment (viewport + remaining) on
  both platforms.
- [x] Phase 5 bounded iOS prepared-history cache and 4,096-event staging with
  authoritative overflow recovery on both platforms.
- [x] Phase 6 verification closeout: focused suites green on both platforms;
  architecture document describes the implemented pipeline; acceptance gates
  closed. Intentional parity gaps remain in
  [parity-gaps.md](./parity-gaps.md).
- [x] The implemented message display and rendering architecture is documented
  from `DisplayMessage` through timeline projection, Screen Model state, native
  row rendering, markdown paint, and auto-follow on both platforms in
  [message-display-rendering-pipeline.md](./message-display-rendering-pipeline.md).
- [x] The follow-up reliability and responsiveness architecture is documented
  as a seven-phase cross-platform plan in
  [message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md).
- [x] Hardening Phase 4: independent streaming text lane (format-always 16/48)
  plus disposable decoration lane with bounded backlog on both platforms.
- [x] Hardening Phase 5: stable `blockID`s, internal DisplayFrame→TimelineFrame
  projectors, and per-block Markdown via BlockDecorationCache on both platforms.
- [x] Hardening Phase 6: Screen Model `timelineFrame` reuse, declared-ID host
  apply, and unarmed visible-anchor restore on both platforms.
- [x] Hardening Phase 7a: soak matrix mapped, unused probes deleted, architecture
  docs flipped to implemented; Phase 7b deferred.
- [x] OPE-92 format-always: docs and soak naming updated from plain-source
  first paint to format-first scaffolds (structure/semantic), with TextMate
  color enrichment still newest-first when enabled (2026-07-31).

## Design-system

- [x] Existing `OpenCodeRenderedMarkdown` consumes prepared markdown plus
  streaming state; no new component is required.

## Android

- [x] `MessageSync` subscribes before initial REST, merges ordered text deltas,
  revision-gates REST replacements, and retains REST part identities so live
  updates preserve sibling parts.
- [x] `InMemoryMessageStore` serializes nested store reads and mutations.
- [x] Failed idle reconciliation clears streaming state so polling can recover.
- [x] Large-history enrichment cancellation does not join stale synchronous
  parsing before processing a newer live snapshot; large finalized annotated
  strings are built off the UI thread.
- [x] Timeline apply coalescing preserves explicit refresh and post-send
  completion callbacks.
- [x] Focused sync, preparation, pipeline, scroll, and Session Chat tests cover
  the changed behavior.
- [x] Phase 6: `timelineFrame` on UiState with composer reuse; Ready path uses
  declared `inserted ∪ layoutChanged` layout follow (no history-wide
  `layoutSignature`); unarmed visible-anchor ID+offset capture/restore on
  layout-affecting applies (view-owned; separate from armed follow).

## iOS

- [x] `MessageSync` subscribes before initial REST, merges ordered text deltas,
  revision-gates REST replacements, and retains REST part identities so live
  updates preserve sibling parts.
- [x] Main-actor store mutations provide atomic revision checks.
- [x] Failed idle reconciliation clears streaming state so polling can recover.
- [x] Large-history enrichment is cancellable and markdown parsing remains off
  `MainActor`.
- [x] Timeline apply work is lifecycle-cancelled and coalesces live updates.
- [x] Focused sync, preparation, pipeline, rendered-markdown, and Session Chat
  tests cover the changed behavior.
- [x] Phase 6: `timelineFrame` on UiState with composer/session-info/review/
  command reuse; `MessageListTimelineHost` applies declared IDs only (no
  list-wide Equatable); unarmed visible-anchor ID+offset capture/restore on
  layout-affecting applies; cell-apply coalesce during drag retained.

## Blockers

None. The message-display-rendering-hardening plan is closed via Phase 7a.

## Remaining work

- Phases 1–7a of
  [message-display-rendering-hardening-plan.md](./message-display-rendering-hardening-plan.md)
  are complete. Soak matrix:
  [phase-7a-soak-matrix.md](./phase-7a-soak-matrix.md) (scenarios 1–8 **full**
  after OPE-87).
- Phase 7b Slice 1 (soak coverage) shipped under
  [OPE-87](https://linear.app/opencode-mobile/issue/OPE-87/fill-phase-7a-soak-coverage-gaps)
  / umbrella
  [OPE-86](https://linear.app/opencode-mobile/issue/OPE-86/message-display-rendering-phase-7b).
  Plan: [message-display-rendering-7b-plan.md](./message-display-rendering-7b-plan.md).
- **Parked** (reopen triggers only; not scheduled): public `DisplayFrame`,
  Sync/Pipeline absorption, fingerprint→revision, change-set hint, `syncHealth`
  UI, device p95 CI, sole-text bridge deletion.
