# Unified Diff

## Intent

Render one file's read-only line changes in a compact mobile review surface.
Use it when a feature has structured unified hunks and needs stable line
numbers, semantic addition/deletion treatment, and source formatting without
exposing editing actions.

## Public API

- Variants: collapsed context and expanded available context.
- States: default, reviewed, and selected hunk.
- Required content: file path, file status, additions, deletions, and a parsed
  Unified Diff document containing typed hunks and rows with old/new line
  numbers and row kinds.
- Optional content: selected hunk identifier.
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
- Keep source rows unwrapped and horizontally scrollable while the owning page
  scrolls vertically.
- Collapsed context is the default and shows limited unchanged lines around
  each change. Expanded mode reveals all unchanged context present in the
  loaded patch without requesting or inventing additional source lines.
- Reviewed presentation remains readable but reduces diff emphasis. It does not
  remove or collapse the file.
- A selected hunk receives semantic focus/selection treatment without changing
  the patch contents.
- Missing or empty patches are handled by the owning screen as unavailable
  previews, including binary files.

Accessibility exposes the file summary before the source rows and describes
row kind plus old/new line numbers without reading decorative `+` or `-`
markers twice.

## Platform Notes

- iOS: native SwiftUI rows inside coordinated vertical and horizontal scroll
  containers.
- Android: native Compose rows inside coordinated vertical and horizontal
  scroll containers.
- Both platforms use equivalent unified-patch fixtures and existing syntax-role
  mappings; renderer implementation remains native.

## Review Surfaces

- Register an interactive Unified diff entry in both Component Galleries.
- Include light/dark examples for modified, added, deleted, reviewed,
  collapsed, expanded, long-line, and unavailable-patch cases.

## Parity And Divergence

Row meaning, line numbering, context behavior, syntax roles, reviewed emphasis,
and accessibility meaning are shared. Native scrolling mechanics may differ
internally but should not create observable behavior differences.

## Completion Ledger

- Shared public API and semantic states: complete.
- Token and typography usage: complete; the component uses the documented
  semantic diff, syntax, surface, border, caption, and code roles.
- iOS implementation: complete, including typed hunks and rows, selected-hunk
  focus, horizontal source scrolling, context variants, and reviewed emphasis.
- Android implementation: complete with the equivalent behavior and native
  bring-into-view handling for selected hunks.
- Component Galleries: complete on both platforms with interactive modified,
  added, deleted, unavailable, reviewed, collapsed/expanded context, Unicode,
  no-newline, multi-hunk, and long-line fixtures under gallery light/dark theme
  controls.
- Parity and divergences: complete; no observable divergence is known.
- Automated checks: complete for focused parser and Changes Screen Model tests,
  Android debug assembly, and the iOS simulator build and tests.
