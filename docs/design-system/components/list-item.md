# List Item

## Intent

Provide a reusable row treatment for selectable project and session lists with a
consistent rounded surface, title-first hierarchy, and optional trailing
accessory.

## Public API

- Variants: default, selected.
- States: enabled, disabled.
- Required content: `title`.
- Optional content: `subtitle`, trailing accessory content.

## Token Usage

- Semantic tokens: `color.surface.primary`, `color.surface.secondary`,
  `color.text.primary`, `color.text.secondary`, `color.text.disabled`,
  `color.border.subtle`, `color.icon.default`.
- Component tokens: none yet; this component currently maps directly to semantic
  tokens.
- Typography roles: `type.heading` for title and `type.caption` for subtitle.

## Behavior

The component supports tap/click actions when a handler is provided. Selected
rows use the secondary surface token. Disabled rows reduce emphasis and do not
run actions. Long title/subtitle strings truncate to one line.

## Platform Notes

- iOS: implemented with plain `Button` when action exists, otherwise static
  content.
- Android: implemented with `Surface` and optional `Modifier.clickable`.

## Previews

- Light: included.
- Dark: included.
- Default: included.
- Pressed: covered through platform interaction behavior.
- Disabled: included.
- Loading: not applicable for this component.
- Error: not applicable for this component.

## Parity And Divergence

No intentional divergence currently. Both platforms share the same variant and
state semantics.
