# Tool Call Card

## Intent

Render a **collapsed**, **tool-agnostic** row for an assistant tool call. Feature
screens use this when Session Chat (or any timeline) needs a readable tool
status card without expanding into tool-specific body content.

The component does not know tool IDs, navigate to Changes or child sessions, or
expand. Feature code builds title and subtitle copy (for example `Called edit`),
supplies optional +/− counts and error text, and optionally wires a tap.

## Public API

- Variants (status):
  - `pending`
  - `running`
  - `completed`
  - `error`
- States:
  - default display when no tap handler is provided
  - pressed / active: native pressed feedback only when a tap handler is provided
  - focused: platform default for tappable cards; not a separate visual variant
  - disabled: not applicable — omit the handler for display-only, or provide one
    for interaction; no separate disabled API in this version
  - loading: not applicable — `pending` / `running` are status variants with
    static chrome only (no shimmer or animation)
  - error: not a separate component or overlay state; use the `error` status
    variant plus optional error text
  - empty: not applicable — title is required
  - selected: not applicable
- Required content:
  - `title`
- Optional content:
  - `subtitle`
  - `additions` / `deletions` (nonnegative counts; omit a side when nil or zero)
  - `errorText` (typically paired with the `error` status)
  - feature-owned tap (`action` / `onClick`); display-only when omitted

## Token Usage

- Semantic tokens: `color.surface.base`, `color.border.weaker`,
  `color.text.strong`, `color.text.base`, `color.text.weaker`, `success`,
  `warning`, `error`, `diffAdd`, `diffDelete`.
- Component tokens: `toolCallCard.*` in [`../tokens.md`](../tokens.md).
- Typography roles:
  - `type.heading` for the title
  - `type.caption` for subtitle, +/− counts, and error text

## Behavior

The card is a leading-aligned bordered surface with a status dot, title, and
optional supporting lines. Status maps to semantic colors:

| Status | Dot token |
| --- | --- |
| `pending` | `warning` |
| `running` | `warning` |
| `completed` | `success` |
| `error` | `error` |

When both a subtitle and +/− counts are present, they share one caption row:
subtitle on the leading side, counts trailing. Error text, when present, appears
below that row in the `error` color.

Positive addition and deletion counts render as `+N` and `−N` (Unicode minus)
using `diffAdd` / `diffDelete`. Zero or nil counts are omitted.

Without a tap handler the card is display-only. With a handler, the whole card
is interactive and uses native pressed feedback clipped to the card shape. The
component never navigates on its own.

Accessibility treats the card as a single element. The combined label includes
status, title, subtitle, counts, and error text. Tappable cards expose a button
role.

## Platform Notes

- iOS: SwiftUI `Button` with plain style when tappable; otherwise a static
  surface. Status chrome is a filled circle; no shimmer.
- Android: Compose `Surface` with clipped ripple when `onClick` is provided;
  otherwise non-clickable. Status chrome is a filled circle; no shimmer.

## Review Surfaces

- iOS: Component Gallery → Tool Call Card (`ToolCallCardGalleryView`) with
  interactive status, content, and tappable controls; light/dark via gallery
  theme controls. `#Preview` cases cover each status.
- Android: Component Gallery → Tool Call Card (`ToolCallCardGalleryScreen`) with
  the same interactive controls; light/dark via gallery theme controls.
  `@Preview` cases cover each status.

## Parity And Divergence

Shared semantics for layout, status mapping, and optional content are aligned
across iOS and Android. No intentional divergences for this component.

## Completion Ledger

- Public API and applicable states: complete (`pending` / `running` /
  `completed` / `error`; display-only or feature-owned tap).
- Token and typography mapping: complete; uses `toolCallCard.*` geometry plus
  shared status and diff semantic colors with `type.heading` / `type.caption`.
- iOS and Android implementations: complete.
- Gallery and previews: complete with interactive status, content, and
  tappable controls plus light/dark gallery theme support.
- Parity and divergences: complete; none known.
- Automated checks: Android unit tests for accessibility-label composition pass;
  Android debug compile and iOS simulator build pass.
