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
- Required content: positive changed-file count and action.
- Optional content: none.
- Loading, error, selected, and empty states are not applicable; callers hide
  the component until a positive count is available.

The component pluralizes its fixed product copy:

- `Review 1 changed file`
- `Review N changed files`

## Token Usage

- Semantic tokens: `surface-raised-base`, `surface-raised-base-hover`,
  `text-strong`, `icon-strong-base`, and `border-weaker-base`.
- Component tokens: `reviewChangesPill.*` in `../tokens.md`.
- Typography role: `type.button` in `../typography.md`.

## Behavior

Tapping the enabled pill invokes the supplied action. Disabled presentation
reduces emphasis and blocks interaction.

The caller owns visibility. Session Chat shows it only when the latest user
turn is complete, that turn changed at least one file, and the timeline is at
its end. It is mutually exclusive with Scroll to end.

The accessibility label matches the fully pluralized visible label. Native
pressed feedback uses the shared semantic surface mapping.

## Platform Notes

- iOS: native SwiftUI `Button` with a capsule surface and light shadow.
- Android: Material 3 clickable `Surface` with capsule shape, border, and
  elevation.

## Review Surfaces

- Both Component Galleries register an interactive Review Changes Pill entry
  with singular/plural count and enabled controls.
- SwiftUI previews and Compose previews provide stable light and dark singular,
  plural, and disabled states.

## Parity And Divergence

The public copy, visibility contract, interaction, geometry, and accessibility
meaning are shared. No intentional platform divergence is planned.

## Completion Ledger

- Public API and applicable enabled/disabled states: complete.
- Token and typography mappings: complete; the implementations use the
  documented `reviewChangesPill.*` geometry, raised semantic surfaces, and
  button typography.
- iOS and Android implementations: complete.
- Gallery entries and native previews: complete.
- Automated interaction, accessibility-label, pluralization, visibility, and
  navigation checks: complete.
- Parity and divergences: complete; no observable divergence is known.
