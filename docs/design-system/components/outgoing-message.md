# Outgoing Message

## Intent

Render a user's outgoing prompt inside a session conversation. Use this
component when a feature needs to show a sent mobile prompt as a compact,
trailing-aligned message card.

This component is intentionally outgoing-only. Assistant and tool messages need
separate treatment before they become reusable design-system components.

Message actions such as restore and copy are not part of this component. Session
Chat handles those through a feature-local bottom sheet when the user taps an
outgoing message.

## Public API

- Variants:
  - default outgoing prompt card.
- States:
  - default full-height display.
- Required content:
  - metadata label using dot-separated sent-message context.
- Optional content:
  - message text.
  - one or more `OpenCodeMessageAttachment` presentation values.
  - rendered markdown document and initial height cache for prepared content.
  - tap or long-press interaction handled by the owning feature.

## Token Usage

- Semantic tokens: `color.surface.inset`, `color.text.primary`,
  `color.text.tertiary`.
- Component tokens: `outgoingMessage.cornerRadius`,
  `outgoingMessage.maxWidthFraction`, `outgoingMessage.paddingHorizontal`,
  `outgoingMessage.paddingVertical`, `outgoingMessage.footerSpacing`.
- Typography roles:
  - `type.body` for message text.
  - `type.caption` for footer metadata.

## Behavior

Messages render trailing aligned in their parent conversation row. When text is
present, the rounded bubble may grow with message content up to the shared
`outgoingMessage.maxWidthFraction` cap so long prompts wrap naturally. Short
prompts shrink to their content width; there is no minimum bubble width. The
message text is shown without a role prefix; alignment communicates that the
message is outgoing. The bubble uses `color.surface.inset` so it
remains distinct from the page background in light mode without introducing a
component-only raw color. Bubble corners match `listItem.cornerRadius`.

Attachments render as a separate row of `OpenCodeMessageAttachment` items
inside the same outgoing-message grouping. Attachment-only messages omit the
empty text bubble but retain the attachment row and metadata footer.

Outgoing message content is always rendered at full height. Long prompts do not
collapse in place, which keeps message height deterministic after optimistic
send and markdown preparation.

Every outgoing message includes a tiny footer directly below the rounded bubble.
The right-aligned footer label uses compact caption typography and combines
sent-message context as `mode · model · time`, for example
`Build · DeepSeek V4 Flash · 9:05 PM`.

When the owning feature enables message actions, only the rounded message pill
is interactive. Press feedback is clipped to the pill shape; the metadata
footer and unused conversation-row width do not activate the message.

## Platform Notes

- iOS: implemented with SwiftUI text inside the rounded message surface. The
  bubble caps at `outgoingMessage.maxWidthFraction` and otherwise shrink-wraps
  to content width. Feature-owned tap or long-press gestures attach to the pill.
- Android: implemented with Compose text inside a Material 3 surface. Ripple
  feedback is clipped to the pill shape.

## Previews

- Light: included.
- Dark: included.
- Default: included with short copy.
- Long: included with long copy.
- Pressed: Android uses its native clipped ripple in the interactive gallery;
  iOS has no persistent pressed styling, so a separate pressed preview is not
  applicable.
- Disabled: not applicable in this component version.
- Loading: not applicable in this component version.
- Error: not applicable in this component version.

## Parity And Divergence

Shared semantics for layout and full-height overflow behavior are aligned
across iOS and Android. Width measurement has one intentional iOS implementation
divergence documented in `../divergences.md`.
