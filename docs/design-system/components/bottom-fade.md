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

- iOS: `LinearGradient` sized to the measured dock or composer block plus the
  home-indicator inset. Home Session List extends the fade with
  ignore-safe-area. Session Chat's keyboard host already ignores the bottom
  safe area so the timeline and fade reach the physical edge; the composer
  keeps the home-indicator lift.
- Android: `Brush.verticalGradient` full-width box aligned to the physical
  bottom. Height includes the navigation-bar inset when the dock or composer
  block is measured with `navigationBarsPadding`. The navigation host must not
  consume that bottom inset on Home or Session Chat, or the fade stops above
  the bar and the screen reads as a slab on the system navigation.

## Review Surfaces

Session List gallery (fade under dock).

## Parity And Divergence

Shared geometry and opacity stops. No intentional divergence beyond platform
gradient APIs.
