# Review Changes Pill

## Intent

Provide a compact floating entry point for inspecting files changed by the
latest completed turn while the user is at the end of Session Chat.

This is a separate component from `OpenCodeActionPill`: its label is dynamic,
its visibility depends on completed-turn change data, and its action navigates
away from the timeline rather than moving the current scroll position.

## Public API

- Variants: one semantic `latestTurn` variant.
- States: enabled and disabled.
- Required content: positive changed-file count, nonnegative addition total,
  nonnegative deletion total, and action.
- Optional content: none.
- Loading, error, selected, and empty states are not applicable; callers hide
  the component until a positive count is available.

The component presents a compact latest-turn summary. It pluralizes the file
copy and omits each zero-valued line total:

- `1 file  +12  −3`
- `N files  +12  −3`
- `N files  +12` when deletions are zero.
- `N files  −3` when additions are zero.
- `N files` when both line totals are zero.

## Token Usage

- Semantic tokens: `surface-raised-base`, `surface-raised-base-hover`,
  `text-strong`, `text-base`, `diff-add`, `diff-delete`, and
  `border-weaker-base`.
- Component tokens: `reviewChangesPill.*` in `../tokens.md`.
- Typography role: `type.button` in `../typography.md`.

## Behavior

Tapping the enabled pill invokes the supplied action. Disabled presentation
reduces emphasis and blocks interaction.

The caller owns visibility. Session Chat shows it only when the latest user
turn is complete, that turn changed at least one file, and the timeline is at
its end. It is mutually exclusive with Scroll to end.

The enabled file summary uses `text-strong`, additions use `diff-add`, and
deletions use `diff-delete`. Disabled content uses the existing disabled text
treatment. The pill has no leading icon. Its summary is vertically centered
within the capsule on both platforms.

Accessibility identifies the control as the Review changes action and speaks
the visible totals using pluralized phrases, for example `Review changes, 5
files, 12 additions, 3 deletions`. Zero-valued addition or deletion phrases are
omitted, matching the visible summary. Native pressed feedback uses the shared
semantic surface mapping.

## Platform Notes

- iOS: native SwiftUI `Button` with a capsule surface and light shadow.
- Android: Material 3 clickable `Surface` with capsule shape, border, and
  elevation. A centered inner content container owns the minimum height so
  font metrics cannot top-align the summary within the capsule.

## Review Surfaces

- Both Component Galleries register an interactive Review Changes Pill entry
  covering mixed totals, additions-only, deletions-only, zero-line,
  singular/plural, and enabled/disabled controls.
- SwiftUI previews and Compose previews provide stable light and dark coverage
  across mixed totals, additions-only, deletions-only, zero-line,
  singular/plural, and enabled/disabled states.

## Parity And Divergence

The public copy, visibility contract, interaction, geometry, and accessibility
meaning are shared. No intentional platform divergence is planned.

## Completion Ledger

- Public API and applicable enabled/disabled states: complete for a positive
  file count and nonnegative addition/deletion totals.
- Token and typography mappings: complete; the implementations use the
  documented `reviewChangesPill.*` geometry, raised semantic surfaces, and
  button typography.
- iOS and Android implementations: complete with equivalent required totals,
  formatting, semantic colors, disabled treatment, interaction, and
  accessibility behavior.
- Gallery entries and native previews: complete across mixed totals,
  additions-only, deletions-only, zero-line, singular/plural, enabled/disabled,
  and light/dark states.
- Automated formatting, zero-omission, interaction, accessibility,
  pluralization, visibility, and navigation checks: complete on both
  platforms. Android device execution remains subject to the repository's
  existing instrumentation build health.
- Parity and divergences: complete; no observable divergence is known.
