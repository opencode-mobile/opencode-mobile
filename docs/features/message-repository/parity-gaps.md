# Message Repository — Parity Gaps

## Command and tool message UI

- **Intent:** Readable timeline for all server message roles.
- **iOS:** Fallback row (role label + text).
- **Android:** Fallback row (role label + text).
- **Area:** Visual semantics / missing implementation.
- **Reason:** Design-system components for command/tool messages not built yet.
- **Next action:** Add components when message UX is defined.
- **Status:** Temporary.

## Composer selectors mock

- **Intent:** User can pick model, agent mode, and Thinking Effort before send.
- **iOS:** Local chip state only; not sent to server.
- **Android:** Local chip state only; not sent to server.
- **Area:** Behavior.
- **Reason:** Text-only send in this slice; server option lists deferred.
- **Next action:** Map chips to request body when server lists exist.
- **Status:** Temporary.

## No live assistant streaming

- **Intent:** Timeline updates as the assistant works.
- **iOS:** Foreground SSE reloads messages on bus events (see [Foreground Event Stream](../foreground-event-stream/progress.md)).
- **Android:** Foreground SSE reloads messages on bus events (see [Foreground Event Stream](../foreground-event-stream/progress.md)).
- **Area:** Behavior.
- **Reason:** Addressed by Foreground Event Stream slice; incremental part streaming still deferred.
- **Next action:** Optional targeted updates from `message.part.updated` payloads.
- **Status:** Temporary (full reload only).

## Assistant messages without text parts

- **Intent:** Show meaningful assistant content when the server returns no text parts.
- **iOS:** Tool-part fallback summary (e.g. tool name); `info.error` message when the provider fails.
- **Android:** Tool-part fallback summary (e.g. tool name); `info.error` message when the provider fails.
- **Area:** Behavior.
- **Reason:** Minimal v1 mapping until rich assistant UI exists. Provider errors are surfaced as plain text; reasoning and other non-text part types are not rendered yet.
- **Next action:** Rich part rendering in Session Chat.
- **Status:** Temporary (tool/reasoning parts); provider error text is shipped.
