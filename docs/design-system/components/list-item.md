# List Item

## Intent

Provide a reusable row treatment for selectable project and session lists with a
title-first hierarchy, optional **start decorator** (letter badge or icon),
optional footer metadata (status + caption), and optional trailing accessory.

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
- Optional content: plain `subtitle`, semantic subtitle segments, `status`,
  `caption`, and trailing accessory content. Plain subtitle and semantic
  segments are mutually exclusive.
- Semantic subtitle segment tones: `secondary`, `addition`, and `deletion`.
  They allow structured metadata such as diff totals without exposing raw
  colors through the component API.
- **Status** (`OpenCodeListItemStatus`, optional): text plus a semantic tone.
  Status tones: `interactive`, `success`, `warning`, `error`, `info`. The
  status piece is a filled dot and text that share the tone color.
- **Caption** (optional): low-contrast supporting text shown beside status in
  the footer row.
- **Footer row**: appears when either `status` or `caption` is present.
  Leading-aligned layout: `[statusDot + statusText]  caption` on one line.
- **Start decorator** (`none` by default):
  - **`none`**: no leading visual.
  - **`letter`**: one uppercase character in a **36×36** rounded square; callers
    choose a **`LetterAccent`** semantic variant (paired fill and tint from
    component tokens). The component keeps a single grapheme and applies
    uppercase styling where appropriate.
  - **`icon`**: **24×24** Material catalog icon (Android: `ImageVector` from
    `OpenCodeIcons` plus optional content description; iOS: `OpenCodeIcon` plus
    optional accessibility label). Tint follows row emphasis (including disabled).

Internal typography for the letter uses an **18pt** semibold glyph sized for the
**36×36** badge; this is not a separate public typography override.

## Token Usage

- Semantic tokens: `color.surface.primary`, `color.surface.secondary`,
  `color.text.primary`, `color.text.secondary`, `color.text.disabled`,
  `color.text.weaker`, `color.border.subtle`, `color.icon.default`, `diff-add`,
  `diff-delete`, `interactive`, `success`, `warning`, `error`, and `info`.
- Component tokens: outer row corner radius — `listItem.cornerRadius` in
  `docs/design-system/tokens.md`; status chrome — `listItem.statusDotSize` and
  `listItem.statusDotSpacing` in the same file; letter badge accents and inner
  badge radius — see `listItem.startDecorator.*` in the same file.
- Typography roles: `type.heading` for title and `type.caption` for subtitle,
  status text, and caption.

## Behavior

The component supports tap/click actions when a handler is provided. Selected
rows use the secondary surface token. Disabled rows reduce emphasis and do not
run actions. Long title/subtitle/footer strings truncate to one line.

Semantic subtitle segments render as one line using the caption typography
role. Secondary segments use secondary text, addition segments use `diff-add`,
and deletion segments use `diff-delete`. Disabled presentation resolves every
segment to the disabled text treatment.

The optional footer renders below the subtitle (when present). Status maps tones
to semantic status colors for both the dot and the status text. Caption uses
`color.text.weaker`. When disabled, status and caption both resolve to the
disabled/weaker text treatment with no colored status chrome.

`card` style adds the elevated surface fill and subtle border. Unselected
`plain` rows do not draw an outer container. Selected `plain` rows and all
`card` rows use the shared row corner radius.

When the start decorator is **`letter`**, the badge is top-aligned with the
title column. Icon and undecorated rows stay vertically centered.

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
- Subtitle: **plain**, **semantic diff**, and **off** gallery controls cover the
  mutually exclusive subtitle forms and optional slot.
- Footer: **off**, **status+caption**, **status only**, and **caption only**
  gallery controls; status tone picker covers all five tones with sample copy
  (`Working · 14m`, `sidebar-v2-concepts`).
- Start decorator: letter accents and icon are explored via **interactive
  controls** in the component gallery (single preview row), not a static matrix
  of samples.
- Card style: included in platform previews.
- Loading: not applicable for this component.
- Error: not applicable for this component.

## Parity And Divergence

Shared semantics for variants, states, and decorator roles. Platform-specific
icon identifier types are documented in `docs/design-system/divergences.md`.
