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

## Blockers

None.

## Remaining work

None for the pipeline-hardening plan. Follow-up product work (if any) should
open a new feature slice rather than reopen Phases 1–6.
