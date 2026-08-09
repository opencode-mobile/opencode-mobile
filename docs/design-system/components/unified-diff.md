# Unified Diff

## Intent

Render one file's read-only line changes in a compact mobile review surface.
Use it when a feature has structured unified hunks and needs stable line
numbers, semantic addition/deletion treatment, and source formatting without
exposing editing actions.

## Public API

- Variants: collapsed context and expanded available context.
- States: default and selected hunk.
- Required content: domain `FileDiff` (path, status, additions, deletions) and a
  parsed Unified Diff document containing typed hunks and rows with old/new line
  numbers and row kinds.
- Optional content: selected hunk identifier; Android-only
  `onSelectedHunkOffset(y)` callback so an owning scroll container can bring
  the selected hunk into view.
- Loading, error, and empty states are not applicable; the owning screen
  presents those states before constructing the renderer.

## Token Usage

- Semantic tokens: background and surface roles, `border-weaker-base`, text
  hierarchy, `diff-add`, `diff-delete`, and the existing `syntax-*` roles.
- Component tokens: `unifiedDiff.*` in `../tokens.md`.
- Typography roles: `type.code` for source and gutters, `type.caption` for hunk
  metadata.

## Behavior

- Render old/new line-number gutters and addition, deletion, context, and hunk
  header rows.
- Keep parsing deterministic across platforms: hunk headers expose old/new
  ranges, rows retain stable patch-line identifiers, metadata does not advance
  source line numbers, and malformed or non-text patches produce no hunks.
- Keep source rows explicitly single-line and unwrapped. Content wider than the
  viewport expands to its intrinsic width and remains horizontally scrollable
  while the owning page scrolls vertically; it is never line-broken or
  ellipsized to fit.
- Fill the available horizontal viewport with the diff surface even when every
  rendered source row is shorter than the viewport. Long rows may still expand
  the horizontal scroll content beyond that minimum width.
- Collapsed context is the default and shows limited unchanged lines around
  each change. Expanded mode reveals all unchanged context present in the
  loaded patch without requesting or inventing additional source lines.
- A selected hunk receives semantic focus/selection treatment without changing
  the patch contents.
- Missing or empty patches are handled by the owning screen as unavailable
  previews, including binary files.

Accessibility exposes the file summary before the source rows and describes
row kind plus old/new line numbers without reading decorative `+` or `-`
markers twice.

## Platform Notes

- iOS: native SwiftUI rows inside coordinated vertical and horizontal scroll
  containers. The horizontal content stack adopts its widest row's intrinsic
  width so long lines produce a real scroll range.
- Android: native Compose rows inside coordinated vertical and horizontal
  scroll containers. When `onSelectedHunkOffset` is provided, selected hunk rows
  report their Y offset for parent scroll coordination.
- Both platforms use equivalent unified-patch fixtures and existing syntax-role
  mappings; renderer implementation remains native.

## Review Surfaces

- Register an interactive Unified diff entry in both Component Galleries.
- Include light/dark examples for modified, added, deleted, collapsed,
  expanded, long-line, and unavailable-patch cases.

## Parity And Divergence

Row meaning, line numbering, context behavior, syntax roles, and accessibility
meaning are shared. Native scrolling mechanics may differ
internally but should not create observable behavior differences.

## Completion Ledger

- Shared public API and semantic states: complete.
- Token and typography usage: complete; the component uses the documented
  semantic diff, syntax, surface, border, caption, and code roles.
- iOS implementation: complete, including typed hunks and rows, selected-hunk
  focus, explicit single-line intrinsic sizing, full-width surface layout,
  horizontal source scrolling, and context variants.
- Android implementation: complete with the equivalent behavior and native
  no-wrap row sizing plus bring-into-view handling for selected hunks.
- Component Galleries: complete on both platforms with interactive modified,
  added, deleted, unavailable, collapsed/expanded context, Unicode,
  no-newline, multi-hunk, and long-line fixtures under gallery light/dark theme
  controls.
- Parity and divergences: complete; no observable divergence is known.
- Automated checks: complete for focused parser and Changes Screen Model tests,
  Android debug assembly, and the iOS simulator build and tests.
