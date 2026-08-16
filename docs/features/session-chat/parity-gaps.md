# Session Chat Parity Gaps

## Temporary — OPE-170 device smoke

- Date: 2026-08-15
- Expected shared intent: A child session shows lockout (model + effort, no
  prompt) and Back returns to the parent; permission/question still replace it.
- Current iOS state: implemented; device smoke not run in this change.
- Current Android state: implemented; unit tests pass; device smoke not run.
- Affected area: behavior
- Reason: composer-kind unit tests cover priority; live child navigation needs
  a device session with a `task` child.
- Owner / next action: open a child session on both platforms before merge.
- Status: temporary

## Intentional — OPE-145 Android 16dp scroll-pill hide slack

- Date: 2026-08-14
- Expected shared intent: Hide Scroll to end during user scroll and while
  settling to end; show only after the viewer stops past the reveal threshold.
- Current iOS state: `ScrollToEndVisibility.shouldShow` hides on user scroll;
  `TimelineScrollController` also hides during programmatic scroll. No hide slack.
- Current Android state: same user-scroll and settle gates, plus 16dp hide slack
  so reveal-threshold jitter cannot blink the pill.
- Affected area: behavior
- Reason: Android LazyList layout jitter around the reveal band was the reported
  blink; iOS distance-from-bottom is stable enough without slack.
- Owner / next action: add the same slack on iOS only if a blink appears there.
- Status: intentional

## Temporary — OPE-113 device smoke

- Date: 2026-08-07
- Expected shared intent: After send, Stop and Working appear immediately; mid-turn
  open shows waiting chrome; Android failed status GET does not clear busy.
- Current iOS state: implemented in source; device smoke not run in this change.
- Current Android state: implemented; focused unit tests pass; device smoke not run.
- Affected area: behavior
- Reason: automation covers LiveStore/adapter/indicator gating and optimistic
  clear rules; live SSE timing needs device confirmation.
- Owner / next action: run Session Chat smoke on both platforms before merge.
- Status: temporary

## Intentional — OPE-113 Working-row host insertion

- Date: 2026-08-07
- Expected shared intent: Show the same Working/Retrying muted row when the
  session is busy and the active turn has no visible assistant content yet.
- Current iOS state: synthetic `SessionChatRow` appended in
  `SessionChatTimelineView` (`session-chat-working-indicator`); host skips apply
  packets while present.
- Current Android state: dedicated LazyColumn footer via
  `MessageListTimeline` `workingIndicatorKey` / `workingIndicatorContent`.
- Affected area: state model
- Reason: platform host list identity differs; product gating is shared in
  `WorkingTurnIndicator`. See [decisions.md](./decisions.md).
- Owner / next action: none while hosts remain separate.
- Status: intentional

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
