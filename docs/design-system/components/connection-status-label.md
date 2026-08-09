# Connection Status Label

## Intent

Show foreground SSE connection state as a compact status dot plus lowercase
label. Use it wherever live server connectivity should read at a glance without
competing with primary screen titles.

The first product use is the Session screen top-bar subtitle accessory
(`OpenCodeTopBar.subtitleAccessory.statusDot`). Do not use this label for
session context usage; that belongs in `session-status-bar.md`.

## Public API

- Variants: single compact inline label.
- States:
  - `live` — connected and receiving foreground events.
  - `connecting` — reconnect or handshake in progress.
  - `offline` — disconnected or unavailable.
- Required content: one `OpenCodeSessionConnectionStatus` value.
- Optional content: none.

Status copy is fixed lowercase English strings: `live`, `connecting`,
`offline`.

## Token Usage

- Semantic tokens:
  - dot fill: `success` (`live`), `warning` (`connecting`), `error` (`offline`).
  - label color: `text.base` (iOS) / on-surface variant (Android).
- Component tokens:
  - `connectionStatusLabel.dotSize` — 6 logical px.
  - `connectionStatusLabel.dotSpacing` — 4 logical px between dot and label.
- Typography roles: `type.caption` (iOS) / body small (Android).

## Behavior

Renders a leading circular dot and trailing single-line status text. The row
does not truncate the dot; label text ellipsizes when space is tight.

Accessibility announces **Live updates {status}** (for example **Live updates
live**). The dot and label are grouped as one accessibility element on iOS.

Pressed, focused, disabled, loading, error, empty, and selected states are not
applicable — the label is presentational and non-interactive.

## Platform Notes

- iOS: `OpenCodeConnectionStatusLabel` SwiftUI view; dot uses semantic
  `OpenCodeColors.success` / `warning` / `error`.
- Android: `OpenCodeConnectionStatusLabel` composable; dot uses `openCodeColor`
  semantic mappings with matching geometry.

## Review Surfaces

No standalone gallery entry. Review through the **Top Bar** gallery when
`subtitleAccessory.statusDot` is populated, and in Session Chat / Session screen
previews that surface live connection state.

## Parity And Divergence

Shared status enum, copy, dot colors, geometry, and accessibility intent are
aligned across iOS and Android. Typography mapping follows each platform's
caption / body-small role without inventing a third label style.
