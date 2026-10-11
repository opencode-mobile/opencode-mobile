# Status Stack

## Intent

`OpenCodeStatusStack` is the centered mid-well indeterminate status for a screen
region waiting on first content. Use it for AppRoot bootstrap, Home
probing/loading, and Session Chat timeline loading. Do not use it for soft
refresh overlays, empty instructional states, or composer busy.

## Public API

- Variants: none (single mid-well layout).
- States: `active` (indeterminate animation) only. Pressed, focused, disabled,
  error, empty, selected, and determinate progress are not applicable.
- Required content: non-empty `title` and `subtitle`.
- Optional content: platform test / accessibility identifier for UI tests.

Do not expose tint color, stroke width, hex, or arbitrary size as public API.
Ring size is locked at ~32pt in the implementation.

## Token Usage

- Semantic tokens: transparent background (inherits well); ring stroke
  `text-base`; ring track `border-weaker-base` (fallback `text-weaker`); title
  `text-base`; subtitle `text-weaker`.
- Component tokens: none.
- Typography roles: `type.body` for title; `type.caption` for subtitle.

## Behavior

- Vertically and horizontally centered in the caller-provided space.
- Indeterminate ring; never uses interactive / brand / primary accent tint.
- Non-interactive. Title + subtitle combine into one accessible progress
  announcement; the ring is decorative when text conveys status.
- Callers supply locked product copy for each surface.

## Platform Notes

- iOS: SwiftUI stack with `ProgressView` tinted to `text-base` (preserves
  `activityIndicators` UITest queries when an accessibility id is set) plus
  `OpenCodeTypography` / `OpenCodeColors`.
- Android: Compose column with Material 3 `CircularProgressIndicator` using
  `text-base` / `border-weaker-base` via `openCodeColor`, not `primary`.

## Review Surfaces

- Android Component Gallery entry **Status Stack**.
- iOS Component Gallery entry **Status Stack**.
- Preview: Home copy and Session copy samples; light and dark via gallery theme
  controls.

## Completion Ledger

- Public API and applicable states: complete.
- Token and typography mapping: complete.
- iOS and Android implementations: complete.
- Gallery entries and previews: complete.
- Parity and divergences: complete; none intentional.
- Automated checks: tracked with feature implementation.

## Parity And Divergence

None. Track temporary feature wiring gaps on the GitHub issue.
