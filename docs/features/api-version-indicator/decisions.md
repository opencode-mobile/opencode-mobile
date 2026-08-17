# API Version Indicator — Decisions

## Rows and Test, not home

- Date: 2026-08-16
- Decision: Show API v1/v2 on Connection Profile rows (home Servers sheet and
  Settings → Servers) and on successful Test copy. Do not put it on the home
  top-bar live/offline subtitle.
- Reason: Protocol is a property of a saved server, useful when switching among
  mixed v1/v2 profiles. Home chrome already answers connectivity. Locked from
  prototype variants B + C; A was rejected.
- Prototype: `docs/prototypes/api-version-indicator/`.

## Unknown protocol is omitted

- Date: 2026-08-16
- Decision: If `serverProtocol` is null, show no API caption and no v1/v2 pill.
  Active still shows **Active**. Failed Test does not mention API version.
- Reason: The value is detected, not configured. Guessing v1 would lie.
