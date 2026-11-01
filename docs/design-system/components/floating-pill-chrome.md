# Floating Pill Chrome

## Intent

Provide the shared capsule-shaped glass / raised-surface material recipe used
by floating session-chat pills: Floating Pill, Action Pill, and Review Changes
Pill.

Callers own label content, tap targets, press feedback, and layout positioning.
This helper owns only the capsule fill/material and enabled/disabled opacity.

Use when a floating pill should read in the same chrome family without adopting
rectangular Floating Chrome (`floating-chrome.md`) used by the composer and
dock.

## Public API

- Variants: single capsule material recipe.
- States:
  - **enabled** (default opacity and material emphasis).
  - **disabled** (reduced opacity / underlay emphasis).
- Required content: slotted body content inside the capsule.
- Optional content: none beyond the enabled flag.

Pressed, focused, loading, error, empty, and selected states are owned by the
caller's button style, not the chrome itself.

Do not expose raw colors, opacities, elevation values, or shadow parameters as
public customization beyond the documented recipe.

## Token Usage

- Semantic tokens: `surface.raised-base` underlay at 0.92 opacity (iOS glass
  stack); `border.weaker-base` on pre–iOS 26 fallback; Android pills use the
  same raised-surface family inline.
- Component tokens: capsule geometry comes from each pill's content padding and
  `actionPill.*` / sibling pill tokens — see `../tokens.md`.
- Typography roles: not owned by the chrome.

## Behavior

Presentational material only. Clips content to a capsule. Does not apply outer
float insets or inner label padding.

On iOS, press feedback stays on `OpenCodeFloatingPillPressStyle` (subtle scale
and opacity). The chrome deliberately avoids interactive Liquid Glass lens
effects.

When multiple glass pills stack on iOS 26+, callers should wrap them in
`GlassEffectContainer` so materials compose correctly.

## Platform Notes

- iOS: `View.openCodeFloatingPillChrome(enabled:)` —
  iOS 26+ non-interactive `glassEffect(.regular)` with raised underlay; below
  iOS 26 raised capsule + weaker border + light shadow.
- Android: no separate helper type. Each pill composable applies the Material 3
  raised `Surface` stand-in (fill alpha 0.95, light elevation, weaker border)
  documented under Action Pill and sibling pill specs.

## Review Surfaces

Reviewed through **Floating Pill**, **Action Pill**, and **Review Changes Pill**
gallery entries on both platforms. No standalone gallery
entry — the helper is not a product surface by itself.

## Parity And Divergence

Intentional material difference: iOS Liquid Glass capsule vs Android raised
Surface stand-in. Documented in `../divergences.md` (Floating dock / floating
chrome materials). Shared intent is a compact floating capsule that reads above
scrolling chat content.
