# Composer Liquid Glass Progress

## Shared Planning

- Status: implementation complete on branch; awaiting visual review + PR.
- Linear: [OPE-99](https://linear.app/opencode-mobile/issue/OPE-99/composer-liquid-glass)
- Branch: `feature/ope-99-composer-liquid-glass`
- Related: Floating Dock materials from `liquid-glass-home-list` (OPE-84);
  keyboard lift must not regress (OPE-93).

## Design System

- Shared Floating Chrome extracted (`OpenCodeFloatingChrome` /
  `openCodeFloatingChrome`); Floating Dock consumes it.
- Docs: `floating-chrome.md`; Session Composer + suggestion overlay contracts
  updated; divergences cover floating chrome family; tokens note composer
  shell uses Floating Chrome.

## iOS

- Status: composer shell + suggestion overlay use floating chrome; gutters
  unchanged; continuous radius 26 on composer.
- Model / mode / thinking-effort pickers use `OpenCodeChoiceBadge` glass
  variant + `GlassEffectContainer`. Chip row uses size-based horizontal bounce
  so it only scrolls when chips overflow.
- Timeline scrolls under the floating composer stack with home-list bottom fade;
  end clearance is composer height + **16** only (no extra 12 content padding) so
  the resting gap above the shell is 16. iOS uses `additionalSafeAreaInsets`
  with Session Chat section bottom `0`; Android LazyColumn bottom padding is the
  overlay inset alone.
- Checks: spacing tweak after LLDB clearance fix.

## Android

- Status: composer shell + suggestion overlay use floating chrome; gutters
  unchanged; Floating Dock refactored onto shared helper.
- Model / mode / thinking-effort pickers use `OpenCodeChoiceBadge` glass
  variant (home-dock-aligned capsule chrome). Chip row enables horizontal
  scroll only when measured content exceeds composer width.
- Timeline scrolls under the floating composer stack with matching bottom fade /
  inset (composer + 16 overhang).
- Checks: `compileDebugKotlin` green after fade parity.

## Blockers

- None.

## Remaining Work

- [ ] Manual gallery / Session Chat visual review (light + dark, permission
      outline on glass, messages visible under composer while scrolling)
- [ ] PR open + merge

## Completed

- [x] Accept open decisions in `decisions.md`
- [x] Design-system: shared floating chrome helper
- [x] iOS composer materials
- [x] Android composer materials
- [x] Suggestion overlay shell alignment (same family)
- [x] Cap suggestion list at 4 rows; snap filter updates; bottom-anchor overlay
- [x] Float composer over timeline so messages show through glass
- [x] Parity / divergence docs (bottom fade still deferred)
- [x] Restore `plan.md` onto branch; Linear In Progress
