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

## Parity And Divergence

List intentional differences or link to `../divergences.md`.
```

## Definition Of Done

- The component intent and public API are documented.
- Android and iOS implementations are added together once app projects exist.
- Both platform component registries or galleries list the component.
- Both detail or preview routes open successfully.
- Light and dark previews exist.
- Relevant states are covered with stable copy and realistic data.
- Token and typography usage maps back to `docs/design-system/tokens.md` and
  `docs/design-system/typography.md`.
- Intentional platform differences are documented in the same change.
