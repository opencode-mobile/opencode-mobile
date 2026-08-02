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

- Semantic tokens: shared Floating Chrome recipe (`surface.raised` underlay;
  see `floating-chrome.md`).
- Component tokens: continuous corner radius 26pt/dp.
- Typography roles: not owned by the surface.

## Behavior

The dock is a presentational surface. Callers own padding of outer insets
(horizontal float and bottom safe-area / nav-bar padding). Content is clipped to
the continuous rounded rect. Materials come from `OpenCodeFloatingChrome` /
`openCodeFloatingChrome`.

## Platform Notes

- iOS: uses shared floating chrome (iOS 26+ glass + underlay; material fallback).
  Dock owns outer float insets `16` / `4` and inner content padding `12`.
- Android: uses shared floating chrome (raised Surface stand-in). Outer float
  insets remain caller-owned; dock owns inner content padding `12`.

## Review Surfaces

Session List gallery (dock in context). Optional standalone gallery entry when
needed for surface-only review.

## Parity And Divergence

Intentional material difference: iOS Liquid Glass vs Android raised Surface.
Documented in `../divergences.md` and `floating-chrome.md`.
