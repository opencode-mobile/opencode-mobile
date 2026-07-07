# Outgoing Message

## Intent

Render a user's outgoing prompt inside a session conversation. Use this
component when a feature needs to show a sent mobile prompt as a compact,
trailing-aligned message card.

This component is intentionally outgoing-only. Assistant and tool messages need
separate treatment before they become reusable design-system components.

## Public API

- Variants:
  - default outgoing prompt card.
- States:
  - default full-height display.
- Required content:
  - message text.
  - metadata label using dot-separated sent-message context.
  - restore action.
- Optional content:
  - rendered markdown document and initial height cache for prepared content.

## Token Usage

- Semantic tokens: `color.surface.inset`, `color.text.primary`,
  `color.text.tertiary`, `color.icon.default`.
- Component tokens: `outgoingMessage.cornerRadius`,
  `outgoingMessage.maxWidthFraction`, `outgoingMessage.paddingHorizontal`,
  `outgoingMessage.paddingVertical`, `outgoingMessage.contentSpacing`,
  `outgoingMessage.controlItemSpacing`,
  `outgoingMessage.actionIconTouchTarget`, `outgoingMessage.actionIconSize`.
- Typography roles:
  - `type.body` for message text.
  - `type.caption` for footer metadata.

## Behavior

Messages render trailing aligned in their parent conversation row. The rounded
bubble uses the shared width intent from `outgoingMessage.maxWidthFraction` so
outgoing prompts read as a consistent column, even when the message text is
short. The message text is shown without a role prefix; alignment communicates
that the message is outgoing. The bubble uses `color.surface.inset` so it
remains distinct from the page background in light mode without introducing a
component-only raw color. Bubble corners match `listItem.cornerRadius`.

Outgoing message content is always rendered at full height. Long prompts do not
collapse in place, which keeps message height deterministic after optimistic
send and markdown preparation.

Every outgoing message includes a tiny footer directly below the rounded bubble.
The right-aligned footer label uses compact caption typography and combines
sent-message context as `mode · model · time`, for example
`Build · DeepSeek V4 Flash · 9:05 PM`.

The bubble's bottom control row places compact restore/copy actions on the
right. The restore action must not delete or resend the original message. The
controls expose `Restore message to composer` and `Copy message` accessibility
labels.

## Platform Notes

- iOS: implemented with SwiftUI text and catalog icon buttons inside the rounded
  message surface (`undo` and `content_copy` from the shared catalog). The
  trailing column uses a stable leading gutter instead of SwiftUI
  `containerRelativeFrame`; see
  `../divergences.md#outgoing-message-width-measurement`.
- Android: implemented with Compose text and icon buttons inside a Material 3
  surface (`OpenCodeIcons.Undo`, `OpenCodeIcons.ContentCopy`).

## Previews

- Light: included.
- Dark: included.
- Default: included with short copy.
- Long: included with long copy.
- Pressed: covered by footer button feedback.
- Disabled: not applicable in this component version.
- Loading: not applicable in this component version.
- Error: not applicable in this component version.

## Parity And Divergence

Shared semantics for layout and full-height overflow behavior are aligned
across iOS and Android. Width measurement has one intentional iOS implementation
divergence documented in `../divergences.md`.
