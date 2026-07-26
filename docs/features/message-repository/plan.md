# Message Repository

## User Goal

Open a session from home, see authoritative server message history, send a new
prompt asynchronously, and watch the assistant response grow in near real time
while the app remains in the foreground.

## In Scope

- `MessageRepository` with list, get, sync send, and async prompt routes.
- Hand-written HTTP clients and domain adapter on iOS and Android.
- `api/opencode-messages.openapi.yaml` maintained until `/doc` includes message paths.
- `SessionChatViewModel` wired to load history, optimistic send, reload after
  success, and preserve the pending send state across live refreshes.
- `MessageSync` and `InMemoryMessageStore` combine an authoritative REST
  snapshot with ordered foreground SSE message patches.
- Live `message.part.delta` text streaming with bounded display-update
  coalescing, finalization on full-part or `session.idle`, and a changed-message
  watchdog poll when text is not actively streaming.
- Pull-to-refresh, reload on screen appear, and authoritative synchronization
  after stream connection or reconnection signals.
- Responsive display preparation for large histories and large streaming
  markdown messages.

## Out Of Scope

- Background or guaranteed event delivery. SSE is foreground-only and REST
  remains authoritative after reconnect.
- Dedicated command/tool message UI components.
- Rendering reasoning parts in the normal assistant transcript.
- Attachment upload, permissions, abort, diff from chat.
- Connection Profile Keychain storage.
- App-build OpenAPI codegen.

## Server APIs Involved

- `GET /session/:id/message`
- `GET /session/:id/message/:messageID`
- `POST /session/:id/message`
- `POST /session/:id/prompt_async`
- `GET /global/event` — foreground SSE, owned by the
  [Foreground Event Stream](../foreground-event-stream/plan.md) slice.

## Domain Objects

- `Message`, `MessagePart`, `MessageRole`, `MessageError`,
  `MessageRepository`.
- `MessageSync`, `MessageStore`, `InMemoryMessageStore`,
  `DisplayMessagePipeline`, and `DisplayMessage`.

## UI States

- Loading, loaded (timeline), empty, error.
- Sending flag and optimistic user row while `prompt_async` is in flight.
- Streaming assistant text while live deltas arrive; finalized content after a
  full-part update or authoritative idle reload.
- Plain-first large-history or large-streaming markdown frame while enriched
  rendering is prepared away from the UI thread.

## Error States

Repository maps transport failures to `MessageError`; Screen Model surfaces
product-language copy.

An initial history failure produces the screen error state. Send failure removes
the optimistic row and restores the composer. If the authoritative
`session.idle` reload fails, local streaming state ends and the watchdog resumes
so a later REST request can recover without leaving the message permanently
streaming.

## Permission Behavior

Not applicable; permission requests are handled by the Permission Approval
slice rather than message transport.

## Design-System Components

- `OpenCodeOutgoingMessage` for user rows.
- `OpenCodeRenderedMarkdown` for formatted assistant and outgoing text,
  including its streaming state and large-content plain-first behavior.
- Feature-local neutral fallback row for command/tool/unknown roles until their
  dedicated reusable message UI is defined.

## Navigation

- Android: `SessionChat` destination via `MainNavigation`.
- iOS: `SessionChatView` from `ProjectListHomeView` navigation destination.

## Expected Logs

- Message REST loads record the session identifier, reason, item count, and
  duration without message bodies.
- Foreground event logs record event type and session identifier without prompt
  text, credentials, authorization headers, or response bodies.
- Display preparation may record timing and item counts, but never rendered
  message content.
