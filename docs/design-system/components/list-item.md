# List Item

## Intent

Provide a reusable dense row for selectable project and session lists: circular
letter badge (or icon), title-first hierarchy, optional subtitle, and optional
trailing meta (caption above a status pill).

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
  `caption`, trailing accessory content, optional `onClick`/`action`, and
  optional `onLongPress`. Plain subtitle and semantic segments are mutually
  exclusive.
- Semantic subtitle segment tones: `secondary`, `addition`, and `deletion`.
- **Status** (`OpenCodeListItemStatus`, optional): text plus a semantic tone
  rendered as a **capsule pill** (not a leading dot).
- **Caption** (optional): low-contrast text stacked **above** the status pill in
  a trailing meta column, end-aligned with the pill.
- **Start decorator** (`none` by default):
  - **`none`**: no leading visual.
  - **`letter`**: one uppercase character in a **32×32** circle; callers choose a
    **`LetterAccent`**. Glyph is **14pt** bold rounded. Optional **`statusDot`**
    (`OpenCodeListItemStatusTone`) draws a **10pt** filled circle at the
    bottom-trailing corner of the badge, ringed with `background-base` so it
    sits on the circle edge. Omit the dot when status is only shown elsewhere
    (or not at all).
  - **`icon`**: **24×24** Material catalog icon.

## Token Usage

- Semantic tokens: surface, text strong/base/weaker, border subtle, icon,
  `diff-add` / `diff-delete`, status tones (`interactive`, `success`, `warning`,
  `error`, `info`).
- Component tokens: row corner radius; letter badge size/shape (circle 32);
  status pill padding and capsule shape.
- Typography: body semibold title; caption/label for subtitle, caption meta, and
  11pt semibold status pill label.

## Behavior

Tap/click when a handler is provided. Long-press when `onLongPress` is provided
(and the row is enabled); long-press does not also fire tap. Selected rows use
the secondary surface. Disabled rows reduce emphasis and do not run actions.
Long strings truncate to one line.

When `status` and/or `caption` are set, they form a trailing meta column
(caption on top, status pill below), end-aligned so both share one trailing
edge. Custom `trailing` content (chevrons, etc.) sits after that column.

Status pill fill uses a low-alpha tone wash for interactive/success/warning/error
and a low-alpha `text-strong` wash for info/idle (label uses `text-base`) so Idle
reads as a real pill without an accent color.

## Platform Notes

- iOS: plain `Button` when only tap exists; when long-press is set, use a content
  shape with tap and long-press gestures so long-press does not also open.
- Android: `Surface` with `Modifier.clickable` when only tap exists; use
  `combinedClickable` when long-press is also provided.

## Review Surfaces

Component gallery List Item entry (interactive controls). Session List gallery
shows the same row treatment in the home feed.

## Parity And Divergence

Shared dense layout and status-pill meta. Platform icon identifier types remain
in `docs/design-system/divergences.md`.
