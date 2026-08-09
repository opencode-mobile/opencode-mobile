# Floating Chrome

## Intent

Provide the shared floating glass / raised-surface material recipe used by
Floating Dock, Session Chat Composer, and composer-anchored suggestion
overlays. Callers own layout insets, content padding, and outlines; this helper
owns only the fill/material.

Use when a floating card should read in the same chrome family as the home dock
without adopting dock-specific outer gutters or always-on inner padding.

## Public API

- Variants: single material recipe (`raised` glass-like surface).
- States: default. Pressed, focused, disabled, loading, error, empty, and
  selected are not applicable to the surface itself; content owns those.
- Required content: slotted body content (or modified view content on iOS).
- Optional content: required corner radius (callers typically pass **26** for
  dock/composer shells or **12** for suggestion overlays); Android may pass an
  optional border stroke and elevation overrides.

Do not expose raw colors, opacities, or elevation values as public
customization beyond the documented recipe.

## Token Usage

- Semantic tokens: `surface.raised` underlay (iOS underlay opacity `0.92` under
  glass; Android fill alpha `0.95`).
- Component tokens: continuous corner radius supplied by the caller; common
  values are **26** for dock/composer shells and **12** for suggestion overlays.
- Typography roles: not owned by the surface.

## Behavior

Presentational material only. Does not apply outer float insets or inner
content padding. Clip content to the continuous rounded rect before or with the
material as appropriate on each platform.

## Platform Notes

- iOS: `View.openCodeFloatingChrome(cornerRadius:)` — iOS 26+ `glassEffect` with
  `surface-raised-base` underlay at 0.92; below iOS 26 `.regularMaterial`.
- Android: `OpenCodeFloatingChrome` composable — Material 3 `Surface` with raised
  fill at 0.95 alpha, tonal elevation `2`, shadow elevation `4` (no system
  Liquid Glass). Optional `border` for composer / overlay outlines.

## Review Surfaces

Reviewed through Floating Dock (Session List Dock gallery), Session Composer
gallery, and Composer Suggestion Overlay gallery on both platforms. No
standalone gallery entry — the helper is not a product surface by itself.

## Parity And Divergence

Intentional material difference: iOS Liquid Glass vs Android raised Surface.
Documented in `../divergences.md` (Floating dock / floating chrome materials).
