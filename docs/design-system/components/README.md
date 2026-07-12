# Component Documentation

Each reusable design-system component should have a document in this directory
once the component is defined.

Use this template for `docs/design-system/components/<component-name>.md`.

## Component Template

```markdown
# Component Name

## Intent

What product need this component serves and when feature screens should use it.

## Public API

- Variants:
- States:
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

## Previews

- Light:
- Dark:
- Default:
- Pressed:
- Disabled:
- Loading:
- Error:

When a Component Gallery entry exists, prefer one live component preview plus
interactive controls that switch variants and states instead of a static matrix
of separate samples.

## Parity And Divergence

List intentional differences or link to `../divergences.md`.
```

## Definition Of Done

- The component intent and public API are documented.
- Android and iOS implementations are added together once app projects exist.
- Both platform Component Gallery registries list the component when a gallery
  entry is useful for review.
- Both Component Gallery detail screens or platform previews open successfully.
- Gallery-driven components prefer a single live preview with controls for
  variants and states instead of a static matrix of duplicate examples.
- When a component must be judged edge-to-edge (for example `OpenCodeTopBar`),
  the gallery detail shell may render that entry’s content full width while
  keeping the header, theme controls, and other gallery entries on the normal
  horizontal inset.
- Light and dark previews exist.
- Relevant states are covered with stable copy and realistic data.
- Token and typography usage maps back to `docs/design-system/tokens.md` and
  `docs/design-system/typography.md`.
- Intentional platform differences are documented in the same change.

## Current Component Docs

- `../icons.md` (shared Material icon catalog and sync workflow)
- `outgoing-message.md`
- `top-bar.md`
- `overflow-menu.md`
- `bottom-sheet.md`
- `choice-badge.md`
- `list-item.md`
- `button.md`
- `text-input.md`
- `action-pill.md`
- `project-session-selector.md`
- `session-status-bar.md`
- `rendered-markdown.md`
