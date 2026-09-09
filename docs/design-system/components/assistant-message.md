# Assistant Message

## Intent

Render an assistant reply inside a session conversation. Use this component
when a feature needs to show assistant text as a compact, leading-aligned
message card.

This component is intentionally assistant-text only. Outgoing prompts use
Outgoing Message. Tool calls use Tool Call Card. Child sessions use Child
Session Row.

Message actions such as copy are not part of this component. Session Chat
handles those through a feature-local bottom sheet when the user taps an
assistant message. Late large-markdown paint deferral also stays in Session
Chat; pass `renderedMarkdown` only when the owning feature is ready to paint.

## Public API

- Variants:
  - default leading-aligned assistant card.
- States:
  - default full-height display.
  - loading: not applicable — streaming uses `isStreaming` on rendered
    markdown rather than a separate loading chrome.
  - error: not applicable — Session Chat maps error text into `text`.
  - empty: blank `text` shows the `…` placeholder.
  - pressed / focused / disabled / selected: not applicable — interaction
    belongs to the owning feature.
- Required content:
  - `text` (plain fallback; blank values render `…`).
- Optional content:
  - `renderedMarkdown` document; when present, the card delegates to
    `OpenCodeRenderedMarkdown` with the `assistantMessage` variant.
  - `isStreaming` for the markdown cursor; ignored for plain text.

## Token Usage

- Semantic tokens: `surface-raised-base`, `text-strong`.
- Component tokens: `assistantMessage.cornerRadius`,
  `assistantMessage.paddingHorizontal`, `assistantMessage.paddingVertical`.
- Typography roles:
  - `type.body` for message text.

## Behavior

Messages render leading aligned in their parent conversation row. The rounded
bubble grows with content and has no maximum-width cap. Alignment communicates
that the message is incoming. The bubble uses `surface-raised-base` with no
stroke: the raised fill against `background-base` is the edge. Outgoing
Message uses `surface-interactive-weak` so the two voices do not share a
recipe. Bubble corners match `listItem.cornerRadius`.

When `renderedMarkdown` is present, the card hosts `OpenCodeRenderedMarkdown`
and forwards `isStreaming`. Otherwise it paints plain `type.body` text, or
`…` when `text` is blank.

The component does not own tap or long-press. Features attach those gestures
to the card.

## Platform Notes

- iOS: SwiftUI text or `OpenCodeRenderedMarkdown` inside a raised rounded
  surface with no stroke. Plain text uses `OpenCodeTypography.body` with
  4pt line spacing.
- Android: Compose text or `OpenCodeRenderedMarkdown` inside a raised
  surface with no stroke.

## Review Surfaces

- iOS: Component Gallery → Assistant Message (`AssistantMessageGalleryView`)
  with interactive short, long, and empty samples; light/dark via gallery
  theme controls. `#Preview` covers the same set.
- Android: Component Gallery → Assistant Message
  (`AssistantMessageGalleryScreen`) with the same interactive samples;
  light/dark via gallery theme controls. `@Preview` covers short, long,
  empty, light, and dark.

## Parity And Divergence

Shared semantics for layout, placeholder, and markdown hosting are aligned
across iOS and Android. No intentional divergences for this component.

## Completion Ledger

- Public API and applicable states: complete (one layout; empty placeholder).
- Token and typography mapping: complete; uses `assistantMessage.*` geometry
  plus `surface-raised-base` and `text-strong` with `type.body`.
- iOS and Android implementations: complete.
- Gallery and previews: complete with interactive short/long/empty controls
  plus light/dark gallery theme support.
- Parity and divergences: complete; none known.
- Automated checks: Android unit test for blank-text placeholder.
