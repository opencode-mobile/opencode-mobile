# Composer Liquid Glass Parity Gaps

## Floating chrome materials

- Expected shared intent: Session Composer shell and suggestion overlays use the
  same floating chrome family as Floating Dock.
- Current iOS state: iOS 26+ Liquid Glass + raised underlay; material fallback
  below iOS 26.
- Current Android state: Material raised Surface stand-in (0.95 alpha +
  elevation).
- Affected area: visual semantics / token mapping
- Reason: Android has no system Liquid Glass; matches home dock divergence.
- Owner or next action: none — intentional; recorded in
  `docs/design-system/divergences.md`.
- Status: intentional
