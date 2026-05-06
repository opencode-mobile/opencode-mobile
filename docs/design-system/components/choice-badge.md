# Choice Badge

## Intent

Provide a compact **selectable pill** for **single-choice or toggle-style**
controls in dense UI (for example the component gallery and other control
rows). This is not a notification **dot** badge; it is an interactive choice
control.

## Public API

- Variants: **default** (unselected), **selected**.
- States: **enabled**, **disabled**.
- Required content: text **label**.
- Interaction: **`onClick`** / **`action`** when enabled.

## Token Usage

- Semantic tokens: `color.surface.primary`, `color.surface.secondary`,
  `color.text.primary`, `color.border.subtle`, `color.border.default`.
- Component tokens: geometry for the pill — see `choiceBadge.*` in
  `docs/design-system/tokens.md`.
- Typography roles: `type.button` on iOS for section labels in gallery screens;
  Android maps to `labelMedium` for equivalent density.

## Behavior

Tapping toggles selection only when the caller updates bound state (the badge is
stateless). Disabled badges do not respond and use reduced emphasis. Screen
readers should treat the control as a **toggle** or **button** with an explicit
**selected** state when applicable.

## Platform Notes

- iOS: `Button` with plain style and rounded rectangle stroke/fill.
- Android: `Surface` with `Modifier.clickable` and outline stroke.

## Previews

- Light and dark previews exist in each platform component file.
- The component gallery includes an interactive **Choice badge** entry.

## Parity And Divergence

Visual parity is the goal; minor platform stroke/fill mapping differences belong
in `docs/design-system/divergences.md` only if intentional.
