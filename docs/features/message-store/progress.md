# Message Store — Progress

## Shared planning

- [x] One repository-backed message observation contract is documented for both
  native apps.
- [x] OPE-82 `message.part.delta` streaming, reconciliation, watchdog, and
  content-preparation behavior are documented.

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

None for the documented message-store slice.
