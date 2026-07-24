# Component Documentation

This directory is the authoritative contract for reusable design-system
components. Add one document for each component once its shared intent is
defined.

## Component Contract

- Define public APIs through semantic variants and explicit states.
- Do not expose raw hex colors, one-off gradients, ad-hoc radius values, or
  arbitrary typography overrides as public customization.
- Promote feature-local UI into the Component Collection when it duplicates an
  existing component role, is needed on both platforms, or is likely to recur.
  Feature-local custom UI is appropriate only when reuse or a reusable
  component extension does not fit; document that decision in the feature docs.
- Give iOS and Android one shared intent while preserving native implementation
  and accessibility behavior.

Cover each state that applies: default, pressed or active, focused when
platform-relevant, disabled, loading, error, empty, and selected. Do not invent
unsupported states to fill the list. Record each omitted state as not applicable
in the component document or implementation completion ledger.

## Component Template

Use this template for `docs/design-system/components/<component-name>.md`:

```markdown
# Component Name

## Intent

What product need this component serves and when feature screens should use it.

## Public API

- Variants:
- States (including not-applicable states and reasons):
- Required content:
- Optional content:

## Token Usage

- Semantic tokens:
- Component tokens:
- Typography roles:

## Behavior

Describe interaction, loading, disabled, error, and accessibility behavior.

## Platform Notes

- iOS:
- Android:

## Review Surfaces

List the applicable iOS and Android gallery entries or previews. Follow the
Gallery And Preview Contract in this document.

## Parity And Divergence

List temporary parity gaps in the feature's `parity-gaps.md`. List intentional
differences or link to `../divergences.md`.
```

## Gallery And Preview Contract

- Register the component in both platform Component Galleries when a gallery
  entry is useful for review, and ensure both detail screens or preview routes
  open successfully.
- Prefer one live preview with interactive controls for variants and states,
  rather than a static matrix of duplicate examples.
- Provide light and dark appearances with stable copy and realistic data.
- When a component must be judged edge-to-edge, such as `OpenCodeTopBar`, its
  gallery entry may render full width while the page header, theme controls, and
  other entries keep the normal horizontal inset.

## Definition Of Done

For every item, record `complete`, `not applicable` with a reason, or `blocked`
with evidence:

- The documented and implemented public API satisfies the Component Contract.
- Token and typography usage points to
  [`../tokens.md`](../tokens.md) and [`../typography.md`](../typography.md).
- Android and iOS implementations both satisfy the shared intent.
- Applicable review surfaces satisfy the Gallery And Preview Contract.
- Every platform difference is resolved, recorded as intentional in
  [`../divergences.md`](../divergences.md), or tracked as temporary in the
  feature's `parity-gaps.md`.

The component is complete only when every item is accounted for and every
applicable item is complete.

## Current Component Docs

- `../icons.md` (shared Material icon catalog and sync workflow)
- `attachment.md`
- `outgoing-message.md`
- `tool-call-card.md`
- `top-bar.md`
- `overflow-menu.md`
- `bottom-sheet.md`
- `dialog.md`
- `choice-badge.md`
- `filter-tabs.md`
- `filter-chip.md`
- `floating-dock.md`
- `session-list-dock.md`
- `bottom-fade.md`
- `list-item.md`
- `list-group.md`
- `button.md`
- `text-input.md`
- `action-pill.md`
- `composer-suggestion-overlay.md`
- `review-changes-pill.md`
- `hunk-navigation-pill.md`
- `unified-diff.md`
- `project-session-selector.md`
- `session-list.md`
- `session-status-bar.md`
- `session-chat-composer.md`
- `rendered-markdown.md`
