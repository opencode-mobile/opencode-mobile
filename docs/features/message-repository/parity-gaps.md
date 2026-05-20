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
- **iOS:** Manual refresh only.
- **Android:** Manual refresh only.
- **Area:** Behavior.
- **Reason:** Foreground SSE out of scope for this slice.
- **Next action:** Foreground event stream slice.
- **Status:** Temporary.

## Assistant messages without text parts

- **Intent:** Show meaningful assistant content when the server returns tool/reasoning parts only.
- **iOS:** Adapter fallback summary (e.g. tool name).
- **Android:** Adapter fallback summary (e.g. tool name).
- **Area:** Behavior.
- **Reason:** Minimal v1 mapping until rich assistant UI exists.
- **Next action:** Rich part rendering in Session Chat.
- **Status:** Temporary.
