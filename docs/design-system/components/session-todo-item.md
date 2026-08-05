# Session Todo Item

## Intent

Provide a view-only Session Todo row for Session Todo List surfaces: a leading
status mark plus the todo content, matching desktop dock semantics (checked,
indeterminate, strikethrough muted, weaker pending) with native chrome.

This is separate from `OpenCodeListItem`, which uses letter/icon start
decorators and trailing status pills rather than checkbox-style marks and
strikethrough labels.

## Public API

- Variants: one semantic `statusRow` variant.
- States by status (required):
  - `pending` — empty mark; slightly weaker content
  - `inProgress` — indeterminate mark (accent fill + dash)
  - `completed` — checked mark; strikethrough muted content
  - `cancelled` — checked mark; strikethrough muted content (same chrome as
    completed; cancelled still does not bump progress `N`)
- Required content: `content` (brief description), `status`.
- Optional content: none.
- Loading, error, selected, empty, and interactive tap states are not
  applicable; the row is view-only. Callers omit the row when there is no item.

## Token Usage

- Semantic tokens: `text-strong`, `text-base`, `text-interactive-base`,
  `border-weaker-base`.
- Component tokens: `sessionTodoItem.*` in `../tokens.md`.
- Typography role: `type.body` for content.

## Behavior

The component presents status visually; it does not accept edit or check-off
actions. The status mark is optically centered on the **first line** of content
when the label wraps. Accessibility speaks the content and a status phrase
(`pending`, `in progress`, `completed`, or `cancelled`).

Pending content uses reduced opacity. Completed and cancelled content use
`text-base` with strikethrough. In-progress and pending unfinished content use
`text-strong` (pending still reduced opacity). Status marks use
`text-interactive-base` when active; pending marks use `border-weaker-base`
outline only.

## Platform Notes

- iOS: static `HStack` row (no `Button`); mark is an 18pt rounded square. The
  check glyph uses `OpenCodeIconImage(icon:size:)` (12pt) so asset-catalog
  sizing matches Android — not `.font`.
- Android: non-clickable `Row`; same mark geometry via Compose.

## Review Surfaces

- Both Component Galleries register an interactive Session Todo Item entry
  covering pending, in progress, completed, and cancelled.
- SwiftUI and Compose previews cover light and dark for those statuses.

## Parity And Divergence

Shared mark semantics, typography role, and accessibility phrases. No
intentional platform divergence is planned for the row itself. Sheet-level
progress-header spacing remains a Session Chat concern (see feature
parity-gaps).
