# Streaming UI update backpressure plan

## Status

Implementation complete for Phases 0–5 (uncommitted). Device acceptance
scenarios 1–6 remain the manual pass-bar checklist.

## Objective

Make Session Chat timeline UI updates consistent and less laggy under dense
streaming, large messages, and user scroll — while preserving typed backpressure
across store, display pipeline, timeline region, and list hosts.

Domain coalescing (`DisplayMessagePipeline` 16 ms / 48 ms, plain-first,
decoration backlog) already works. This plan targets **host geometry and scroll
coupling** to every content growth, not TextMate on the UI thread.

Prior diagnosis canvas (workspace-local, not committed):
`session-chat-message-update-architecture.canvas.tsx` and
`session-chat-ui-update-architecture-proposal.canvas.tsx`.

## User-visible outcome

- Armed follow during streaming stays visually smooth: no multi-frame hitch
  tied to each token batch.
- Reading older history while a reply streams does not jump the viewport.
- Drag / fling during stream lets the user’s finger win; brief stale text until
  settle is acceptable.
- Stream end → final markdown produces at most one intentional height jump, not
  a burst of layout thrash.
- Load / refresh / post-send required applies never stick or drop.

## In scope

- `TimelineProjector` / `SessionChatTimelineProjector` content vs layout
  revision policy (stop treating every `textLength` change as layout-affecting).
- Explicit `GrowBottom` / apply-kind semantics for newest-row streaming growth.
- Android: remove streaming `animateContentSize(200 ms)` on the newest row.
- Android: drag-time apply coalesce (parity with iOS pending apply).
- Android: deferred large markdown paint (parity with iOS ≥3k bubble defer).
- Optional UI-frame gate (≤1 live visual apply per vsync) after region coalesce.
- Conceptual `TimelineHostApplier` + `TimelineApply` kinds consumed by
  `MessageListTimeline` / `MessageListTimelineHost`.
- Host apply-kind counters / logs for validation.
- Session Chat feature docs (this plan, progress, decisions, parity).

## Out of scope

- Rewriting `DisplayMessagePipeline` cadence as the primary fix.
- Absorbing Sync / Pipeline into `SessionMessageEngine`.
- Publishing public `DisplayFrame` from the repository (parked Phase 7b).
- Reintroducing history-wide `layoutSignature` / list-wide Equatable discovery.
- Pushing scroll pin state into `SessionChatUiState` / ViewModel.
- Persistent storage, pagination, server protocol, background execution.
- New design-system components unless a host primitive is proven necessary.

## Architectural decisions

### Two revision planes, one row identity

`contentRevision` may change every streaming frame. `layoutRevision` changes
only when geometry policy says so. Hosts must not rediscover layout via
length-as-implicit-layout.

### Typed backpressure

| Plane | May coalesce / drop | Must never drop |
| --- | --- | --- |
| Store / Sync | No accepted text delta | Authority recovery, reload outcomes |
| Display pipeline | Intermediate streaming snapshots; disposable decoration | Finalization when generation current; loadFailed |
| Timeline region | Live frames (latest-wins) | Required load / refresh / send + callbacks |
| Host / UI | Content & Decorate while dragging; at most one GrowBottom / Content per display frame | Structure identity; user scroll intent; Required applies |

### Scroll owns viewport; content owns pixels

Scroll controllers react to **declared layout intents**, not every Screen Model
republish. User drag always wins.

### Streaming growth is a specialized seam

Treat append-at-bottom height growth as `GrowBottom`, not “restyle whole
history.” Prefer a deep apply-kind seam over scattered host tweaks.

### Platform mechanisms, shared contract

Keep reverse `LazyColumn` vs Diffable `UICollectionView`. Align on
`TimelineFrame` / apply-kind semantics, not identical schedulers.

### Do not animate row height while streaming

iOS already has no newest-row size animation. Android must match that intent for
production Session Chat streaming.

## Target apply kinds

| Kind | Meaning | Scroll |
| --- | --- | --- |
| `Structure` | Insert / remove / reorder, tool cards appear | Layout-affecting |
| `Content` | Same height band (streaming text within stable plain metrics) | Rebind pixels only; no follow / anchor restore |
| `GrowBottom` | Newest row taller by Δh | Extend content size; pin if armed |
| `Decorate` | Plain → markdown / enrichment | Coalesce while dragging; prefer deferred paint |
| `Required` | Load / refresh / post-send | Never coalesce away |

## Phases

### Phase 0 — Characterization (S)

Status: Complete.

Findings:

- Android `TimelineProjector.layoutKey` includes `textLength = row.text.length`
  (also block count, markdown presence, streaming, attachments, block
  fingerprints).
- iOS `SessionChatTimelineProjector.layoutFingerprint` includes
  `row.text.count` at the root and in text-block keys.
- Production Android Session Chat wires
  `messageListAnimateLastMessageSize(animateSizeChanges = isNewestMessage)` in
  `SessionChatTimelineView` (gallery uses the same helper).
- Baseline counters:
  - Android `SessionChatTimeline` apply log now includes
    `inserted` / `changed` / `layoutChanged` / `layoutAffectingRev`.
  - Android `SessionChatScroll` logs armed-follow and unarmed-anchor reactions
    to `layoutAffectingRevision`.
  - iOS `timelineApply` logs whenever `layoutAffecting > 0` (plus existing
    size/time thresholds), including `layoutAffecting` / `inserted` / `rev`.
  - iOS scroll log records host `layoutAffecting` on frameRevision change.

Expected: shared baseline metrics for scenarios 1–2 below.

### Phase 1 — First slice: kill size animation + split revisions (S+M)

Status: Complete.

Shipped:

1. Android production Session Chat sets `animateSizeChanges = false` (gallery may
   still demo `messageListAnimateLastMessageSize`).
2. Both projectors omit text length from layout fingerprints; streaming length
   growth no longer bumps `layoutRevision` / `layoutChangedRowIDs`.
3. `TimelineFrame.grewBottomRowIDs` + `growBottomRevision()` declare newest
   streaming text growth. Android armed follow uses
   `MessageListAutoFollowGrowBottomEffect`; unarmed restore still keys only on
   `layoutAffectingRevision`. iOS content-size pin continues to cover armed
   growth while Diffable unarmed restore ignores length-only frames.
4. Projector unit tests cover streaming growth → GrowBottom and streaming end →
   layoutChanged (Android + iOS).

Expected: large lag drop while pinned/streaming; unarmed scroll stops jumping
on every token batch.

### Phase 2 — Apply-kind seam (M)

Status: Complete.

Shipped:

1. Thin `TimelineApply` / `TimelineApplyKind` classify seam on both platforms
   (`Structure`, `Content`, `GrowBottom`, `Decorate`, `Required`).
2. Projectors partition markdown-only layout bumps into `decorateRowIDs`;
   streaming end stays Structure.
3. Regions stamp `isRequired` with the required-queue predicate.
4. iOS `MessageListTimelineApplyPacket` carries grewBottom / decorate /
   isRequired / kinds; Required applies flush immediately under drag.
5. Android Session Chat consumes classify helpers for follow revisions and logs
   kinds; no drag coalesce / md defer yet.
6. Unit tests cover decorate vs structure, multi-kind Required+GrowBottom, and
   iOS Required flush-under-drag.

#### Locked decisions (historical)

1. **Thin classify seam**, not a deep `TimelineHostApplier` rewrite. Diffable /
   Compose ID consumption stays.
2. Canonical surface remains `TimelineFrame` ID sets; `TimelineApply` is a
   **derived** host packet (kinds + helpers + pass-through IDs).
3. Kind set: `Structure`, `Content`, `GrowBottom`, `Decorate`, `Required`. A
   frame may carry **multiple** kinds.
4. Derivation:
   - `Required` ← region `isRequired` (never inferred from IDs)
   - `Structure` ← `inserted ∪ removed` or `layoutChanged − decorate`
   - `Decorate` ← markdown-only subset of `layoutChanged` (`isStreaming`
     unchanged; block/attachment structure unchanged; markdown presence or
     sourceHash changed). Streaming end → **Structure**, not Decorate.
   - `GrowBottom` ← `grewBottomRowIDs`
   - `Content` ← `changed − layoutChanged − grewBottom`
5. Scroll helpers unchanged from Phase 1: `layoutAffecting` excludes GrowBottom;
   unarmed restore keys only on layoutAffecting; armed pin on layoutAffecting
   **or** GrowBottom. Decorate stays layout-affecting in Phase 2.
6. Region stamps `isRequired` using the required-queue predicate
   (`!(fromQuietRefresh && onPublished == null)`).
7. Host MVP: both consume classify for logs; iOS packet gains
   `grewBottom` / `decorate` / `isRequired` / kinds and **Required must flush
   immediately under drag**; Android wires classify helpers + kind logs only —
   **no** drag coalesce / md defer in Phase 2.
8. Gallery `reconfigureAllSurviving` stays as Structure-heavy fallback.

### Phase 3 — Android scroll-time coalesce + markdown defer (M+S)

Status: Complete (uncommitted). Decisions locked 2026-07-28 by triage.

#### Locked decisions

1. Android host-side **latest-wins hold** while `LazyListState.isScrollInProgress`
   (drag + fling). Hold messages + timelineFrame together.
2. Under scroll, flush **Required only**; pend Content / Decorate / GrowBottom /
   Structure (Structure may pend, must not drop).
3. Placement: production `SessionChatTimelineReadyView` + pure policy in
   `ui/scroll/` — not ViewModel/region. Gallery unchanged.
4. Session Chat–only Android markdown defer: `text.length >= 3000` + markdown
   present → plain first, then yield + ~32 ms (match iOS).
5. **Vsync gate OUT** of Phase 3.

#### Delivered

1. `MessageListTimelineApplyHold` + unit tests; wired in
   `SessionChatTimelineReadyView` (shared `listState`, hold messages+frame).
2. `SessionChatMarkdownPaint` (≥3k, yield + 32 ms) in `AssistantMessageBubble`.
3. Vsync gate not implemented (explicitly out).

#### In / out

**In:** Android drag/fling coalesce, Required immediate under scroll, ≥3k
markdown defer in Session Chat `AssistantMessageBubble`, unit tests, docs.

**Out:** Vsync gate, region scroll-aware publishing, Gallery/design-system
markdown defer, Phase 4 patch updates, iOS coalesce changes.

### Phase 4 — Optional deep hot path (L)

Status: Complete (uncommitted). Shipped without waiting on device measurement
(product choice 2026-07-28).

#### Delivered

1. Android `TimelineProjector.mergeMessageItems` mirrors iOS `reusePrevious`:
   unchanged non-pending rows keep prior `SessionChatMessageItem` identity;
   changed / inserted / pending rows rebuild via `toMessageItem`.
2. `SessionChatTimeline.publishDisplayMessages` uses the merge after stamp;
   still publishes a full list each frame (not a patch protocol).
3. Unit tests: history reuse under streaming bottom rebuild; insert + pending
   rebuild.

#### Explicitly out

- Public `DisplayFrame` repository observation
- Host / ViewModel / patch protocol changes
- iOS changes (already had reuse)

### Phase 5 — Closeout

Status: Complete (uncommitted).

- Updated `progress.md`, `parity-gaps.md`, and `decisions.md`.
- Optional A/B flags (`stream_size_anim`, `layout_on_length`,
  `android_drag_coalesce`, `android_md_defer_3k`) were **planning-only** — never
  implemented; Phases 1–3 shipped behavior directly. No flag removal needed.
- Acceptance gates below stay the **manual device checklist** (not auto-closed).
  Run scenarios 1–3 minimum (1–6 full) before treating the pass bar as verified.

## Validation

### Scenarios

1. Armed follow + dense stream (small then ≥8k plain).
2. Unarmed mid-history scroll while stream continues.
3. Drag / fling during stream (Android coalesce).
4. Stream end → final markdown (one height jump).
5. Cold Big Session plain-first + enrich while user scrolls.
6. Post-send required apply under live tokens (callback must fire).

### Metrics

- UI hitch rate / frame time (Android FrameTimeline; iOS Instruments Hitches)
  during scenarios 1–3.
- Counts: `layoutAffecting` applies / sec vs `content` applies / sec;
  GrowBottom Δh events.
- Existing probes: pipeline `displayPublish` / `structuralMs`; add host
  apply-kind counters.

### Optional flags

Planning names only — **not implemented** as runtime toggles. Behavior shipped
directly in Phases 1–3:

- `stream_size_anim=off` → production `animateSizeChanges = false`
- `layout_on_length=off` → projector content/layout split
- `android_drag_coalesce=on` → `MessageListTimelineApplyHold`
- `android_md_defer_3k=on` → `SessionChatMarkdownPaint`

Do not add these flags unless measurement forces an A/B experiment.

### Pass bar

Armed stream stays visually smooth; unarmed scroll does not jump; required send
path never sticks; decoration may lag but must not reorder or duplicate rows.

## What not to do

- Chase TextMate / main-thread parse as the primary stream+scroll fix.
- Absorb Sync / Pipeline into `SessionMessageEngine` to “fix jank.”
- Reintroduce history-wide `layoutSignature` / list-wide Equatable discovery.
- Animate row height during streaming on either platform.
- Push scroll pin state into `SessionChatUiState` / ViewModel.
- Micro-tune only the 16 / 48 ms cadence without the content / layout split.
- Block enrichment behind manual “Load earlier” or shrink history scope.

## Key files

| Area | Paths |
| --- | --- |
| Projectors | `android/.../TimelineProjector.kt`, `ios/.../SessionChatTimelineProjector.swift` |
| Timeline region | `SessionChatTimeline` (both) |
| Android host | `SessionChatTimelineView.kt`, `MessageListTimeline*.kt`, `MessageListTimelineAutoFollowEffects.kt` |
| iOS host | `SessionChatTimelineView.swift`, `MessageListTimelineHost.swift`, `TimelineScrollController.swift` |
| Display pipeline | `DisplayMessagePipeline` (both) — cadence stays; not the primary change |

## Related docs

- [plan.md](./plan.md) — feature-wide Session Chat intent
- [progress.md](./progress.md)
- [decisions.md](./decisions.md)
- [parity-gaps.md](./parity-gaps.md)
- Message-store pipeline hardening (display domain, already shipped):
  `docs/features/message-store/pipeline-hardening-plan.md`
