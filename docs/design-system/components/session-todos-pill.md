# Session Todos Pill

## Intent

Provide a compact floating entry point for an unfinished **Session Todo List**
in Session Chat. The pill shows completed/total progress and the active item
preview; tapping opens the view-only Session Todos sheet.

This is a separate component from `OpenCodeActionPill` and
`OpenCodeReviewChangesPill`: its label is dynamic from Session Todo List state,
and its action opens a sheet rather than scrolling or navigating away.

## Public API

- Variants: one semantic `liveProgress` variant.
- States: enabled and disabled.
- Required content: nonnegative completed count `N`, positive total count `M`
  (`M >= 1`, `N <= M`), optional active preview text, and action.
- Optional content: active preview may be omitted when empty; callers still pass
  progress.
- Loading, error, selected, and empty states are not applicable; callers hide
  the component until Session Todo List visibility rules allow it.

Visible summary:

- Leading accent status dot
- Tabular `N/M`
- Optional truncated active preview after the count

## Token Usage

- Semantic tokens: `surface-raised-base`, `text-strong`, `text-base`,
  `text-interactive-base`, `border-weaker-base` (fallback / Android stand-in).
- Component tokens: `sessionTodosPill.*` in `../tokens.md` (shared floating
  glass capsule geometry with Action / Review Changes pills; max width for
  preview truncation). Shadow elevation is Android / pre–iOS 26 fallback only.
- Typography role: `type.button` in `../typography.md`.

## Behavior

Tapping the enabled pill invokes the supplied action. Disabled presentation
reduces emphasis and blocks interaction.

The caller owns visibility, top-overlay order, hide-while-sheet-open, brief
all-done hold, and permission priority over the sheet. The component only
defines how the pill looks and behaves when shown.

Accessibility identifies the control as Session Todos and speaks progress plus
the preview when present, for example `Session todos, 2 of 5 completed,
Implement auth middleware`.

Pressed feedback uses subtle scale/opacity shared with other floating glass
pills.

## Platform Notes

- iOS 26+: non-interactive `glassEffect(.regular)` capsule via shared
  `OpenCodeFloatingPillChrome`; below iOS 26 raised capsule + border + shadow.
  Session Chat float stacks wrap visible pills in `GlassEffectContainer`.
- Android: Material 3 raised `Surface` stand-in (fill alpha `0.95`, light
  elevation, weaker border). Preview truncates with ellipsis inside a
  max-width constraint.

## Review Surfaces

- Both Component Galleries register an interactive Session Todos Pill entry
  covering with/without preview, mid-progress, near-complete, and
  enabled/disabled controls.
- SwiftUI and Compose previews cover light and dark for those cases.

## Parity And Divergence

The public copy, interaction, geometry, and accessibility meaning are shared.
Material mapping follows the intentional Floating chrome divergence in
`../divergences.md`.
