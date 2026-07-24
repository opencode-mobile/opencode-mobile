# Liquid Glass Home List Parity Gaps

## iOS Liquid Glass vs Android raised surface

- **Expected shared intent:** Same layout hierarchy (dense list, floating dock,
  bottom fade to screen edge) and equivalent product actions.
- **Current iOS state:** Prototype uses iOS 26 `glassEffect` / `.glass` button
  styles with material fallbacks.
- **Current Android state:** Prototype uses Material 3 `Surface` + elevation as
  a glass-like stand-in (no system Liquid Glass API).
- **Affected area:** visual semantics, token mapping
- **Reason:** Platform materials differ; Android has no Liquid Glass equivalent.
- **Owner / next action:** Keep as intentional after production promotion;
  document final fill/elevation choices in design-system divergences if they
  become durable tokens.
- **Status:** intentional (once promoted; temporary while only on prototype
  branch)
