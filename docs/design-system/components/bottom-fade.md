# Bottom Fade

## Intent

Soften scrolling list content as it approaches a floating bottom dock by fading
into `background.base`, extending to the physical screen bottom edge.

## Public API

- Variants: vertical `background.base` fade.
- States: not applicable (non-interactive decoration).
- Required: measured height (typically dock block height + 16pt/dp overhang).
- Optional: none.

## Token Usage

- Semantic tokens: `background.base` at opacity stops `0 → 0.7 → 0.7` from top to
  bottom.
- Geometry: top of fade aligns to dock card top + 16pt/dp; bottom is the physical
  screen edge under the home indicator / nav bar.

## Behavior

Hit-testing disabled. Sits between list and dock in z-order. Does not own dock
safe-area padding.

## Platform Notes

- iOS: `LinearGradient` with bottom safe-area extension via ignore-safe-area on
  the fade only.
- Android: `Brush.verticalGradient` full-width box aligned to bottom; height
  includes navigation bar inset when the dock block is measured with it.

## Review Surfaces

Session List gallery (fade under dock).

## Parity And Divergence

Shared geometry and opacity stops. No intentional divergence beyond platform
gradient APIs.
