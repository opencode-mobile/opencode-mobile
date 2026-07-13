# Unified Diff

## Intent

Render one file's read-only line changes in a compact mobile review surface.
Use it when a feature has structured unified hunks and needs stable line
numbers, semantic addition/deletion treatment, and source formatting without
exposing editing actions.

## Public API

- Variants: collapsed context and expanded available context.
- States: default, reviewed, and selected hunk.
- Required content: file path, file status, additions, deletions, and parsed
  hunks with old/new line numbers and row kinds.
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
- Keep source rows unwrapped and horizontally scrollable while the owning page
  scrolls vertically.
- Collapsed context is the default and shows limited unchanged lines around
  each hunk. Expanded mode reveals all unchanged context present in the loaded
  patch.
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
