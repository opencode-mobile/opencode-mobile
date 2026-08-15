# Message List

## Intent

Provide the shared scrollable chat timeline host for Session Chat and other
screens that need a bottom-anchored, variable-height message column with
auto-follow, scroll-to-end affordance, and optional pull-to-refresh.

The Message List is infrastructure, not a single visual cell. Feature screens
supply row content (for example `OpenCodeOutgoingMessage`,
`OpenCodeAssistantMessage`, and tool rows) through a generic item slot.
Session Chat owns message
presentation; the host owns scroll geometry, pin state, and overlay placement.

## Public API

- Variants:
  - default reversed timeline (newest messages at the bottom).
- States:
  - **pinned to end** / **not pinned** — drives auto-follow and scroll-to-end
    visibility.
  - **auto-follow enabled** / **disabled** — when enabled and pinned, new
    messages and bottom growth keep the viewport at the end.
  - **scroll-to-end pill visible** / **hidden** — shown when the viewport is
    farther than the reveal threshold from the bottom.
  - optional **pull-to-refresh** when a refresh handler is provided (iOS host
    API; Android feature wrappers such as Session Chat).
- Required content:
  - ordered message items with stable string IDs.
  - per-item row content builder.
  - scroll controller / timeline handle wired to the host.
- Optional content:
  - declared apply packet for incremental diff updates (Session Chat).
  - bottom overlay inset for floating composer clearance.
  - horizontal and vertical content padding overrides.
  - keyboard dismiss on user scroll callback (iOS host only).

Shared defaults live in `MessageListTimelineDefaults` on both platforms:

| Token / default | Value |
| --- | --- |
| Horizontal content padding | 16 logical px |
| Top / bottom content padding | 12 logical px |
| Inter-item spacing | 8 logical px |
| Auto-follow tolerance | 32 logical px |
| Reveal threshold minimum | 120 logical px |
| Scroll-to-end pill bottom padding | 12 logical px |
| Pinned tolerance (iOS pin math) | 8 logical px |

## Token Usage

- Semantic tokens: page `background.base`; scroll-to-end overlay uses
  `OpenCodeActionPill` (`action-pill.md`).
- Component tokens: geometry defaults above; no separate visual chrome owned by
  the host itself.
- Typography roles: not applicable — row typography belongs to child message
  components.

## Behavior

Messages render in chronological order with the newest at the bottom. The host
virtualizes long histories and keeps pin math relative to the true content
bottom, including a bottom anchor spacer when needed.

**Auto-follow** re-scrolls to the end on append or bottom growth only while the
user is pinned within the auto-follow tolerance. User scroll away from the
bottom disarms follow until they return or tap scroll to end.

**Scroll to end** uses `MessageListScrollToEndOverlay`, which wraps
`OpenCodeActionPill` (`scrollToEnd`). The pill hides while the user is actively
scrolling and reappears after scrolling settles when the reveal threshold is
still met. Session Chat keeps this pill at the bottom and places Review Changes
in the top overlay instead of hiding one for the other.

**Pull-to-refresh** is available when the host receives a refresh handler;
otherwise the control is omitted. On iOS, `MessageListTimelineHost.onRefresh`
wires a `UIRefreshControl`. On Android, the scroll host does not own refresh;
Session Chat wraps the timeline in `PullToRefreshBox` at the feature layer.

**Keyboard dismiss on scroll** is supported on iOS through
`onUserScrollDismissKeyboard` (the external composer must resign first
responder because `keyboardDismissMode` alone is unreliable). Android does not
expose an equivalent callback on the scroll host.

Accessibility: the scroll-to-end pill inherits Action Pill labels (**Scroll to
end**). Row accessibility belongs to child components.

## Platform Notes

- iOS: `MessageListTimelineHost` wraps a UIKit `UICollectionView` with
  SwiftUI-hosted cells (`MessageListTimelineHostCell`). Pin and programmatic
  scroll use `TimelineScrollController` and absolute bottom offset math
  (`MessageListTimelineBottomOffset`). Session Chat passes declared-ID apply
  packets for streaming updates.
- Android: `MessageListReversedTimelineColumn` with `rememberMessageListTimeline`
  and `MessageListTimelineScrollController`. The list scrolls to a bottom anchor
  item; drag apply coalescing uses `MessageListTimelineApplyHold`.

Intentional host difference (UIKit collection vs Compose lazy list) is documented
in `../divergences.md` (Session chat scroll-to-end on iOS).

## Review Surfaces

- iOS: Component Gallery **Message List** entry — full-height timeline with mock
  outgoing and incoming rows, floating add/remove/resize controls, auto-follow
  toggle, and live scroll-to-end pill.
- Android: matching **Message List** gallery entry with the same interactive
  controls.

## Parity And Divergence

Shared product semantics for pin state, auto-follow gating, reveal threshold,
and scroll-to-end overlay behavior are aligned. Platform scroll hosts differ
intentionally; see `../divergences.md`. Temporary implementation gaps belong
in feature `parity-gaps.md`, not here.
