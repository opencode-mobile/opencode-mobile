# Session Chat Parity Gaps

## Closed — streaming host update + region reuse (Phases 1–4)

- Date: 2026-07-28
- Plan: [streaming-ui-update-backpressure-plan.md](./streaming-ui-update-backpressure-plan.md)
- Closed:
  - Android newest-row `animateContentSize` during streaming (production
    Session Chat `animateSizeChanges = false`; gallery may still demo).
  - Android drag/fling apply coalesce (`MessageListTimelineApplyHold`; Required
    flushes under scroll).
  - Android ≥3k assistant markdown paint defer (`SessionChatMarkdownPaint`).
  - Projector treating streaming `textLength` as layout-affecting (content/layout
    split + `grewBottomRowIDs` / armed GrowBottom).
  - Android full-list `toMessageItem` remap every stream frame vs iOS row reuse
    (**Phase 4:** `TimelineProjector.mergeMessageItems` mirrors iOS
    `reusePrevious`).
- Remaining: manual device acceptance scenarios 1–6 on the plan pass bar.
