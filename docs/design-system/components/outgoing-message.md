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
  - collapsed when content exceeds five rendered lines.
  - expanded after the user opens long content.
- Required content:
  - message text.
  - metadata label using dot-separated sent-message context.
  - restore action.
- Optional content:
  - initial expanded state for previews or restored UI state.

## Token Usage

- Semantic tokens: `color.surface.inset`, `color.text.primary`,
  `color.text.tertiary`, `color.icon.default`.
- Component tokens: `outgoingMessage.cornerRadius`,
  `outgoingMessage.maxWidthFraction`, `outgoingMessage.paddingHorizontal`,
  `outgoingMessage.paddingVertical`, `outgoingMessage.contentSpacing`,
  `outgoingMessage.controlItemSpacing`,
  `outgoingMessage.actionIconTouchTarget`, `outgoingMessage.actionIconSize`,
  `outgoingMessage.chevronTouchTarget`, `outgoingMessage.chevronIconSize`.
- Typography roles:
  - `type.body` for message text.
  - `type.caption` for footer metadata.

## Behavior

Messages render trailing aligned in their parent conversation row. The rounded
bubble always uses `outgoingMessage.maxWidthFraction` of the available row width,
even when the message text is short. The message text is shown without a role
prefix; alignment communicates that the message is outgoing. The bubble uses
`color.surface.inset` so it remains distinct from the page background in light
mode without introducing a component-only raw color. Bubble corners match
`listItem.cornerRadius`.

By default, content longer than five rendered lines is collapsed to five lines.
Expandable messages show an expand/collapse control inside the bubble in the bottom
control row (`expand_content` when collapsed, `collapse_content` when expanded).
Activating the control expands or collapses the text in place.

The control must expose `Expand message` and `Collapse message` accessibility
labels. Short messages that fit within five lines do not show the control.

Every outgoing message includes a tiny footer directly below the rounded bubble.
The right-aligned footer label uses compact caption typography and combines
sent-message context as `mode · model · time`, for example
`Build · DeepSeek V4 Flash · 9:05 PM`.

The bubble's bottom control row places the expand/collapse icon on the left when
it is available, and compact restore/copy actions on the right. The restore
action must not delete or resend the original message. The controls expose
`Restore message to composer` and `Copy message` accessibility labels.

## Platform Notes

- iOS: implemented with SwiftUI text line limiting and catalog icon buttons inside
  the rounded message surface (`OpenCodeIcon.expandContent` /
  `OpenCodeIcon.collapseContent` from Material Symbols; `undo` and `content_copy`
  from the shared catalog).
- Android: implemented with Compose text line limiting and icon buttons inside a
  Material 3 surface (`OpenCodeIcons.ExpandContent`, `OpenCodeIcons.CollapseContent`,
  `OpenCodeIcons.Undo`, `OpenCodeIcons.ContentCopy`).

## Previews

- Light: included.
- Dark: included.
- Default: included with short copy.
- Collapsed: included with long copy.
- Expanded: included with long copy.
- Pressed: covered by expand/collapse and footer button feedback.
- Disabled: not applicable in this component version.
- Loading: not applicable in this component version.
- Error: not applicable in this component version.

## Parity And Divergence

Shared semantics for layout, overflow behavior, and chevron accessibility are
aligned across iOS and Android. No intentional divergence is defined for this
component.
