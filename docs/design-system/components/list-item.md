# List Item

## Intent

Provide a reusable row treatment for selectable project and session lists with a
title-first hierarchy, optional **start decorator** (letter badge or icon), and
optional trailing accessory.

## Public API

- **Container style** (`OpenCodeListItemStyle`, default **`plain`**):
  - **`plain`**: unselected rows are transparent, borderless, and cornerless so
    they blend with scrollable lists. Selected rows use `color.surface.secondary`
    with the shared row corner radius.
  - **`card`**: unselected rows use `color.surface.primary` plus a **1px** stroke
    from `color.border.subtle`; selected rows use `color.surface.secondary` and the
    same stroke and corner radius.
- Variants: default, selected.
- States: enabled, disabled.
- Required content: `title`.
- Optional content: `subtitle`, trailing accessory content.
- **Start decorator** (`none` by default):
  - **`none`**: no leading visual.
  - **`letter`**: one uppercase character in a **24×24** rounded square; callers
    choose a **`LetterAccent`** semantic variant (paired fill and tint from
    component tokens). The component keeps a single grapheme and applies
    uppercase styling where appropriate.
  - **`icon`**: **24×24** Material catalog icon (Android: `ImageVector` from
    `OpenCodeIcons` plus optional content description; iOS: `OpenCodeIcon` plus
    optional accessibility label). Tint follows row emphasis (including disabled).

Internal typography for the letter maps to the **`type.caption`** role with a
stronger weight so the glyph stays legible inside the small badge; this is not a
separate public typography override.

## Token Usage

- Semantic tokens: `color.surface.primary`, `color.surface.secondary`,
  `color.text.primary`, `color.text.secondary`, `color.text.disabled`,
  `color.border.subtle`, `color.icon.default`.
- Component tokens: outer row corner radius — `listItem.cornerRadius` in
  `docs/design-system/tokens.md`; letter badge accents and inner badge radius —
  see `listItem.startDecorator.*` in the same file.
- Typography roles: `type.heading` for title and `type.caption` for subtitle.

## Behavior

The component supports tap/click actions when a handler is provided. Selected
rows use the secondary surface token. Disabled rows reduce emphasis and do not
run actions. Long title/subtitle strings truncate to one line.

`card` style adds the elevated surface fill and subtle border. Unselected
`plain` rows do not draw an outer container. Selected `plain` rows and all
`card` rows use the shared row corner radius.

The letter badge is treated as **decorative** for accessibility when the title
already identifies the row; icons require explicit descriptions on Android and
recommended labels on iOS.

## Platform Notes

- iOS: implemented with plain `Button` when action exists, otherwise static
  content.
- Android: implemented with `Surface` and optional `Modifier.clickable`.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered through platform interaction behavior.
- Disabled: included.
- Container style: **plain** and **card** are toggled via gallery controls.
- Subtitle: **on** and **off** gallery controls pass `nil` when off to show the
  optional subtitle slot.
- Start decorator: letter accents and icon are explored via **interactive
  controls** in the component gallery (single preview row), not a static matrix
  of samples.
- Card style: included in platform previews.
- Loading: not applicable for this component.
- Error: not applicable for this component.

## Parity And Divergence

Shared semantics for variants, states, and decorator roles. Platform-specific
icon identifier types are documented in `docs/design-system/divergences.md`.
