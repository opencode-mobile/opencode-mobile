# Session Status Bar

## Intent

Provide a compact session-scoped status strip for screens that need context
usage in one stable row.

Foreground SSE connection state belongs in the Session screen top-bar subtitle
accessory instead of this strip.

## Public API

- Variants: context usage available / unavailable.
- States: default, pressed for the context action.
- Required content: context action.
- Optional content: context usage fraction. Missing context data renders as
  unavailable instead of 0%.
- Session identity is not shown in this strip. Copy the full session id from the
  Session screen top-bar overflow menu.

## Token Usage

- Semantic tokens: `icon-base`, `icon-strong-base`, `surface-base-hover`,
  `warning`, `error`.
- Component tokens: fixed 36 logical px height, 22 logical px action targets, 14
  logical px context ring.
- Typography roles: not applicable for this component version.

## Behavior

- The context ring is one clickable target for context details.
- The context ring uses `icon-strong-base` for progress and `icon-base` for
  the track.
- Context progress switches to `warning` at 70% and `error` at 85%.
- Pressed feedback is circular for the context action.
- Accessibility labels announce `Context usage <percent> percent` or
  `Context usage unavailable`.

## Platform Notes

- iOS: uses SwiftUI `ButtonStyle` pressed feedback with a circular shape.
- Android: uses Compose `Surface(onClick)` for ripple feedback with a matching
  circular shape.

## Previews

- Light: populated context usage.
- Dark: gallery theme control.
- Default: context availability and context usage preset states (`0%`, `50%`,
  `70%`, `85%`) available in gallery controls.
- Pressed: covered by native pressed interaction behavior.
- Disabled: not supported; the context action is expected to be available.
- Loading: not applicable for this component version.
- Error: represented by high context usage colors at 70% and 85%.
- Empty: context usage unavailable.

## Parity And Divergence

No intentional divergence.
