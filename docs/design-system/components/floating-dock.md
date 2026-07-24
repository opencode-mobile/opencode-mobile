# Floating Dock

## Intent

Provide a floating bottom card surface that hosts compact chrome (filters,
primary actions, optional error) above scrolling content without a sticky
edge-flush footer.

Use when a screen needs a raised dock card that floats above a list and sits
above the home indicator / navigation bar.

## Public API

- Variants: single surface style (`raised` glass-like card).
- States: default. Pressed, focused, disabled, loading, error, empty, and
  selected are not applicable to the surface itself; content owns those.
- Required content: slotted body content.
- Optional content: none at the surface API; callers compose rows inside.

## Token Usage

- Semantic tokens: `surface.raised` underlay (iOS underlay opacity `0.92` under
  glass); `background.base` is not used by the dock fill.
- Component tokens: continuous corner radius 26pt/dp.
- Typography roles: not owned by the surface.

## Behavior

The dock is a presentational surface. Callers own padding of outer insets
(horizontal float and bottom safe-area / nav-bar padding). Content is clipped to
the continuous rounded rect.

## Platform Notes

- iOS: iOS 26+ uses `glassEffect` on a continuous rounded rect with
  `surface-raised-base` underlay at 0.92 opacity; below iOS 26 uses
  `.regularMaterial`.
- Android: Material 3 `Surface` with raised fill at 0.95 alpha, light tonal and
  shadow elevation (no system Liquid Glass).

## Review Surfaces

Session List gallery (dock in context). Optional standalone gallery entry when
needed for surface-only review.

## Parity And Divergence

Intentional material difference: iOS Liquid Glass vs Android raised Surface.
Documented in `../divergences.md` and the liquid-glass-home-list feature
parity-gaps while promoting.
