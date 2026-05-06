---
name: design-system-component
description: Develop opencode-mobile design-system tokens, typography, reusable components, component variants, previews, and galleries. Use when adding or changing native iOS or Android design-system components or when feature work needs reusable UI.
---

# Design System Component

Use this skill when adding or changing the shared Design System or Component
Collection for opencode-mobile.

## Ground Rules

- The canonical source is `docs/design-system/`.
- One conceptual component system serves both native apps.
- Implement Android and iOS together once app projects exist.
- Public APIs should be variant-first and state-aware.
- Do not expose raw hex colors, one-off gradients, ad-hoc radius values, or
  arbitrary typography overrides as public customization.
- Record intentional platform differences in the same change.

## Workflow

1. Read `docs/design-system/tokens.md`,
   `docs/design-system/typography.md`,
   `docs/design-system/components/README.md`, and
   `docs/design-system/divergences.md`.
2. Define the component's shared intent before platform implementation.
3. Add or update `docs/design-system/components/<component-name>.md`.
4. If needed, update token or typography docs before writing component code.
5. Design the public API around semantic variants and explicit states.
6. Once app projects exist, implement iOS and Android in the same change.
7. Update both platform Component Gallery registries, detail screens, controls,
   or preview routes that help review the component.
8. Add light and dark previews with stable copy and realistic states.
9. Document every intentional iOS/Android difference in
   `docs/design-system/divergences.md`.
10. Run the relevant platform build, preview, lint, or test checks when they
    exist.

## Required States

Cover the states that apply to the component:

- default;
- pressed or active;
- focused, if platform-relevant;
- disabled;
- loading;
- error;
- empty;
- selected.

Do not invent unsupported states just to fill a checklist. State omissions should
be intentional and clear from the component docs.

## Definition Of Done

- Component intent and public API are documented.
- Tokens and typography roles map back to the design-system docs.
- iOS and Android implementations are both complete when app projects exist.
- Both platform Component Gallery registries list the component when relevant.
- Light and dark previews exist on both platforms.
- Key states use stable copy and realistic data.
- Temporary mismatches are tracked in the relevant feature `parity-gaps.md`.
- Intentional design-system differences are recorded in
  `docs/design-system/divergences.md`.
