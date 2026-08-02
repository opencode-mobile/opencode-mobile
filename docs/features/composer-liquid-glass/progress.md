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
- Checks: iOS simulator build green.

## Android

- Status: composer shell + suggestion overlay use floating chrome; gutters
  unchanged; Floating Dock refactored onto shared helper.
- Checks: `compileDebugKotlin` green.

## Blockers

- None.

## Remaining Work

- [ ] Manual gallery / Session Chat visual review (light + dark, permission
      outline on glass)
- [ ] PR open + merge

## Completed

- [x] Accept open decisions in `decisions.md`
- [x] Design-system: shared floating chrome helper
- [x] iOS composer materials
- [x] Android composer materials
- [x] Suggestion overlay shell alignment (same family)
- [x] Parity / divergence docs (bottom fade deferred by decision)
- [x] Platform compile checks (iOS sim + Android `compileDebugKotlin`)
- [x] Restore `plan.md` onto branch; Linear In Progress
