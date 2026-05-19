# Session Repository

## User Goal

Load and manage opencode **sessions** from a real server through a shared repository
layer on iOS and Android, starting with the session list home screen.

## In Scope

- `SessionRepository` implementing all [Sessions API](https://opencode.ai/docs/server/#sessions) routes.
- OpenAPI fetch tooling; **sessions** codegen from [`api/opencode-sessions.openapi.yaml`](../../../api/opencode-sessions.openapi.yaml) (server `/doc` is incomplete).
- Domain adapter mapping generated/transport types to product models.
- **Hilt** (Android) and **Factory** (iOS) for dependency injection.
- `ServerConnection` (base URL + optional Basic Auth) defaulting to `AppConfig`.
- Wire session list home to `GET /session`.

## Out Of Scope

- `MessageRepository` and message/prompt routes.
- Connection Profile Keychain storage.
- SSE / foreground event stream.
- Session chat real messages.
- CI OpenAPI fetch.

## Server APIs Involved

All routes under the Sessions table in server docs (`/session`, `/session/status`, `/session/:id`, …).

## Domain Objects

- `Session`, `SessionStatus`, `SessionTodo`, `FileDiff`, `PermissionDecision`, `SessionRepository` (interface).

## UI States

Session list home: loading, loaded, empty, error (unchanged from project-list-home).

## Error States

Generic session-load error; repository maps transport failures to `SessionError`.

## Permission Behavior

`respondToPermission` accepts approve/deny only; **`remember` is not exposed** on the public API.

## Design-System Components

- `OpenCodeListItem` for session rows (existing).
