# Liquid Glass Home List Progress

## Shared Planning

- Status: planned. Prototype on `prototype/liquid-glass` accepted visually;
  production promotion not started.
- Linear: [OPE-84](https://linear.app/opencode-mobile/issue/OPE-84/liquid-glass-home-list)
- Depends on Session List home mode (`OPE-83`) for data wiring and preference.

## Design System

- Not started. Dock, filter chip, dense row, and bottom fade still live only in
  prototype sources.

## iOS

- Prototype variant C wired behind Session List home mode on
  `prototype/liquid-glass` (throwaway `Prototype/` sources).
- Production path still uses pre-prototype Session List chrome on mainline.

## Android

- Prototype parity layout wired behind Session List home mode on
  `prototype/liquid-glass` (throwaway `prototype/` sources).
- Material raised surface stands in for Liquid Glass; fade and dock geometry
  match the accepted iOS prototype.

## Blockers

- None.

## Remaining Work

- [ ] Extract design-system components (dock, filter chip, dense row, bottom fade)
      and gallery entries
- [ ] Promote layout to production home Session List on iOS (replace prototype
      flag / paths; keep iOS 26 glass + material fallback)
- [ ] Promote layout to production home Session List on Android (Material
      glass-like surface + matching fade)
- [ ] Restore loading / empty / error parity with current home list mode
- [ ] Tests for filter/selection chrome wiring and critical layout behavior
- [ ] Remove throwaway prototype sources and flags after promotion
- [ ] Record intentional iOS vs Android material differences in parity-gaps
