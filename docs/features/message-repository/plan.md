# Message Repository

## User Goal

Open a session from home, see real server message history, send a new prompt asynchronously,
and refresh manually when assistant work finishes later.

## In Scope

- `MessageRepository` with list, get, sync send, and async prompt routes.
- Hand-written HTTP clients and domain adapter on iOS and Android.
- `api/opencode-messages.openapi.yaml` maintained until `/doc` includes message paths.
- `SessionChatViewModel` wired to load history, optimistic send, reload after success.
- Pull-to-refresh and reload on screen appear.

## Out Of Scope

- Foreground SSE / live assistant streaming.
- Dedicated command/tool message UI components.
- Attachment upload, permissions, abort, diff from chat.
- Connection Profile Keychain storage.
- App-build OpenAPI codegen.

## Server APIs Involved

- `GET /session/:id/message`
- `GET /session/:id/message/:messageID`
- `POST /session/:id/message`
- `POST /session/:id/prompt_async`

## Domain Objects

- `Message`, `MessageRole`, `MessageError`, `MessageRepository`.

## UI States

- Loading, loaded (timeline), empty, error.
- Sending flag while `prompt_async` is in flight.

## Error States

Repository maps transport failures to `MessageError`; Screen Model surfaces product-language copy.

## Permission Behavior

Not applicable.

## Design-System Components

- `OpenCodeOutgoingMessage` for user rows.
- Simple text bubble for assistant rows; fallback row for command/tool/unknown.

## Navigation

- Android: `SessionChat` destination via `MainNavigation`.
- iOS: `SessionChatView` from `ProjectListHomeView` navigation destination.
