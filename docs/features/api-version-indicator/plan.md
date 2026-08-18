# API Version Indicator

Linear: [OPE-174](https://linear.app/opencode-mobile/issue/OPE-174/show-api-version-on-servers)

Prototype (locked UX):
`docs/prototypes/api-version-indicator/index.html`

## User Goal

See whether a saved Connection Profile speaks **API v1** or **API v2** when
switching or managing servers, and after Test connection. Home session chrome
stays about live/offline, not protocol.

## In Scope

- Show protocol on **profile rows**:
  - Home Servers sheet: Using now and Switch to.
  - Settings → Servers management list.
- Active row: caption `API v1` / `API v2` above the existing **Active** pill.
- Other rows: compact `v1` / `v2` info pill (no Active).
- After a successful Test: `Server reachable · API v1` / `API v2`.
- Omit the indicator when `serverProtocol` is unknown (not yet probed).
- Home top-bar subtitle stays live / connecting / offline only.

## Out Of Scope

- Home title or status-line protocol chrome (prototype variant A, rejected).
- Protocol as a user-editable field.
- Capability warnings (for example last-turn review) as part of this indicator.
- Changing detection or persistence of `ServerProtocol`.

## Server APIs Involved

- None new. Reuses cached `ServerProtocol` from connection probe
  (`GET /global/health` and `GET /api/health`).

## Expected Platform Logs

- Unchanged. Protocol already logs on probe; this slice is display-only.

## Domain Objects

- **Connection Profile** — existing `serverProtocol`.
- **ServerProtocol** — `v1` | `v2` display labels (`v1`/`v2`, `API v1`/`API v2`).

## Screen Model UI States And User Actions

- No new actions. Rows and Test copy read `serverProtocol` already on the
  profile or the last successful test.
- Test success copy is derived from the detected protocol on that test.

## Native Navigation Entry Points

- iOS: `ProjectListHomeView` Servers sheet; `ServersView`; `ConnectionSetupView`.
- Android: `MainScreen` Servers sheet; `ServersScreen`; `ConnectionSetupScreen`.

## Error States

- Failed Test keeps existing error copy; do not append API version.
- Unknown protocol: no caption, no v1/v2 pill.

## Permission Behavior

- Not applicable.

## Design-System Components

- `OpenCodeListItem` — existing `caption` + `status` pill. No new component.
- Test status text — existing body/hint on connection setup. Feature-local copy
  only (`Server reachable · API v2`).
