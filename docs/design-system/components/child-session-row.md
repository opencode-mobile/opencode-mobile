# Child Session Row

## Intent

Render a quiet person/thread row for a child session in Session Chat. Feature
screens use this when a `task` ToolPart should read as someone working in
another session, not as generic tool status.

The component does not know tool IDs, subscribe to a child session stream, or
navigate. Feature code builds title, optional caption, subtitle, and elapsed
copy, and optionally wires a tap.

## Public API

- Variants: one layout. Presence of optional content is not a visual variant.
- States:
  - default display when no tap handler is provided
  - pressed / active: native pressed feedback only when a tap handler is provided
  - focused: platform default for tappable rows; not a separate visual variant
  - disabled: not applicable — omit the handler for display-only
  - loading: not applicable — no spinner, status ring, shimmer, or pulsing dots
  - error: not applicable — error `task` parts keep the Tool Call Card
  - empty: not applicable — title is required
  - selected: not applicable
- Required content:
  - `title`
- Optional content:
  - `caption` (for example `background`)
  - `subtitle` (second line)
  - `elapsed` (feature-owned `m:ss` string; the component does not tick)
  - feature-owned tap (`action` / `onClick`); display-only when omitted
- Derived content:
  - leading letter from `title` (first character, uppercased; `?` when empty)
  - trailing chevron when a tap handler is provided

## Token Usage

- Semantic tokens: `color.surface.base`, `color.surface.raised`,
  `color.border.weaker`, `color.text.strong`, `color.text.base`,
  `color.text.weaker`, `color.icon.base`.
- Component tokens: `childSessionRow.*` in [`../tokens.md`](../tokens.md).
- Typography roles:
  - `type.heading` for the title
  - `type.caption` for caption, subtitle, and elapsed
- Do not use List Item `LetterAccent` colors. The avatar is a letter on
  `surface-raised`.

## Behavior

The row is a leading-aligned bordered surface with a circular letter avatar,
title, optional caption, optional subtitle, optional elapsed, and a trailing
chevron when tappable. There is no status dot and no animated progress.

Without a tap handler the row is display-only. With a handler, the whole row
is interactive and uses native pressed feedback clipped to the row shape. The
component never navigates on its own.

Accessibility treats the row as a single element. The combined label includes
title, caption, subtitle, and elapsed. Tappable rows expose a button role. The
letter avatar and chevron are hidden from the accessibility tree.

## Platform Notes

- iOS: SwiftUI `Button` with plain style when tappable; otherwise a static
  surface. Elapsed, when present, uses monospaced digits.
- Android: Compose `Surface` with clipped ripple when `onClick` is provided;
  otherwise non-clickable. Elapsed uses the caption role.

## Review Surfaces

- iOS: Component Gallery → Child Session Row (`ChildSessionRowGalleryView`)
  with interactive content and tappable controls; light/dark via gallery
  theme controls. `#Preview` cases cover running, background, completed, and
  display-only.
- Android: Component Gallery → Child Session Row
  (`ChildSessionRowGalleryScreen`) with the same interactive controls;
  light/dark via gallery theme controls. `@Preview` cases cover the same set.

## Parity And Divergence

Shared semantics for layout, optional content, and tap behavior are aligned
across iOS and Android. No intentional divergences for this component.

## Completion Ledger

- Public API and applicable states: complete (one layout; display-only or
  feature-owned tap).
- Token and typography mapping: complete; uses `childSessionRow.*` geometry
  plus shared surface, text, and icon tokens with `type.heading` /
  `type.caption`.
- iOS and Android implementations: complete.
- Gallery and previews: complete with interactive content and tappable
  controls plus light/dark gallery theme support.
- Parity and divergences: complete; none known.
- Automated checks: Android unit tests for letter derivation and
  accessibility-label composition; Session Chat mapping tests on both
  platforms.
