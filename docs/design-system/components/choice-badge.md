# Choice Badge

## Intent

Provide a compact **selectable pill** for **single-choice or toggle-style**
controls in dense UI (for example the component gallery and other control
rows). This is not a notification **dot** badge; it is an interactive choice
control.

Use the **glass** variant when the badge sits inside floating glass chrome
(Session Composer model / mode / thinking-effort pickers) and should match the
home dock capsule glass language. Prefer `OpenCodeFilterChip` when the control
is a dock filter with an optional count badge.

## Public API

- Variants: **standard** (default surface pill), **glass** (capsule glass /
  Material stand-in chrome).
- States: **enabled**, **disabled**; **selected** / unselected for both
  variants.
- Required content: text **label**.
- Interaction: **`onClick`** / **`action`** when enabled.

## Token Usage

- Semantic tokens: `color.surface.primary`, `color.surface.secondary`,
  `color.text.primary` / strong / weaker, `color.border.subtle`,
  `color.border.default`.
- Component tokens: geometry for the standard pill — see `choiceBadge.*` in
  `docs/design-system/tokens.md`. Glass variant uses capsule geometry aligned
  with Filter Chip (min height 36, horizontal 12 / vertical 8 padding).
- Typography roles: `type.button` on iOS; Android maps to `labelMedium` /
  `labelLarge` density matching Filter Chip for glass.

## Behavior

Tapping toggles selection only when the caller updates bound state (the badge is
stateless). Disabled badges do not respond and use reduced emphasis. Screen
readers should treat the control as a **toggle** or **button** with an explicit
**selected** state when applicable.

Glass badges in a horizontal row should sit in an iOS `GlassEffectContainer`
when multiple glass surfaces share spacing (Session Composer / home dock
pattern).

## Platform Notes

- iOS standard: `Button` with plain style and rounded-rectangle stroke/fill.
- iOS glass (iOS 26+): selected uses regular (non-interactive) glass capsule with
  low-opacity strong fill; unselected uses clear glass. Press uses subtle
  scale/opacity — not `.glassEffect(...interactive())`. Label color does not
  change with selection. Below iOS 26 selected uses raised surface + weak
  border; unselected uses a light stroke (Filter Chip fallback).
- Android standard: `Surface` with `Modifier.clickable` and outline stroke.
- Android glass: selected fill vs transparent unselected fill; label and border
  stay constant (Material stand-in, same recipe as Filter Chip).

## Review Surfaces

- Light and dark previews in each platform component file.
- Component gallery **Choice badge** entry: standard interactive row, glass
  interactive row, and disabled.

## Parity And Divergence

Visual parity is the goal; Material glass stand-in vs iOS Liquid Glass follows
the Floating Dock / Filter Chip divergence.
