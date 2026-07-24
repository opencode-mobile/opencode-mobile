# Liquid Glass Home List Parity Gaps

## iOS Liquid Glass vs Android raised surface

- **Expected shared intent:** Same layout hierarchy (dense list, floating dock,
  bottom fade to screen edge) and equivalent product actions.
- **Current iOS state:** Production uses iOS 26 `glassEffect` / `.glass` button
  styles with material fallbacks.
- **Current Android state:** Production uses Material 3 `Surface` + elevation as
  a glass-like stand-in (no system Liquid Glass API).
- **Affected area:** visual semantics, token mapping
- **Reason:** Platform materials differ; Android has no Liquid Glass equivalent.
- **Owner / next action:** Intentional; durable entry in
  `docs/design-system/divergences.md` (Floating dock materials).
- **Status:** intentional
