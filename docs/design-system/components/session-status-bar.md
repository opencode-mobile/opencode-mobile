# Session Status Bar

## Intent

Provide a compact session-scoped status strip for screens that need to expose a
copyable session identifier, foreground live-update connection state, and context
usage in one stable row.

## Public API

- Variants: connection status `live`, `connecting`, `offline`.
- States: default, pressed for each action, unavailable context usage.
- Required content: full session id, connection status, copy action, connection
  action, context action.
- Optional content: context usage fraction. Missing context data renders as
  unavailable instead of 0%.

## Token Usage

- Semantic tokens: `text-base`, `icon-base`, `icon-weak-base`,
  `icon-strong-base`, `surface-base-hover`, `success`, `warning`, `error`,
  `interactive`.
- Component tokens: fixed 36 logical px height, 22 logical px action targets, 14
  logical px context ring, 6 logical px connection dot.
- Typography roles: `type.caption`.

## Behavior

- The visible id label is `Session: <last 9 characters>`.
- Only the copy icon copies the raw full session id.
- The connection label and trailing dot are one clickable target for connection
  details, with 4 logical px horizontal spacing.
- Connection dot colors map to status tokens: `live` uses `success`,
  `connecting` uses `warning`, and `offline` uses `error`.
- The context ring is one clickable target for context details.
- The context ring uses `icon-strong-base` for progress and `icon-base` for
  the track.
- Pressed feedback is circular for icon/ring actions and capsule-shaped for the
  connection action.
- Accessibility labels announce `Copy session ID`, `Live updates <state>`, and
  `Context usage <percent> percent` or `Context usage unavailable`.

## Platform Notes

- iOS: uses SwiftUI `ButtonStyle` pressed feedback with circular and capsule
  shapes.
- Android: uses Compose `Surface(onClick)` for ripple feedback with matching
  circular and capsule shapes.

## Previews

- Light: live state with populated context usage.
- Dark: gallery theme control.
- Default: live, connecting, and offline states available in gallery controls.
- Pressed: covered by native pressed interaction behavior.
- Disabled: not supported; all three actions are expected to be available.
- Loading: represented by `connecting`.
- Error: represented by `offline`.
- Empty: context usage unavailable.

## Parity And Divergence

No intentional divergence.
