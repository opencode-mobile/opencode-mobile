# Message Repository — Parity Gaps

## Command and tool message UI

- **Intent:** Readable timeline for all server message roles.
- **iOS:** Fallback row (role label + text).
- **Android:** Fallback row (role label + text).
- **Area:** Visual semantics / missing implementation.
- **Reason:** Design-system components for command/tool messages not built yet.
- **Next action:** Add components when message UX is defined.
- **Status:** Temporary.

## Assistant messages without text parts

- **Intent:** Show meaningful assistant content when the server returns no text parts.
- **iOS:** Tool-part fallback summary (e.g. tool name); `info.error` message when the provider fails.
- **Android:** Tool-part fallback summary (e.g. tool name); `info.error` message when the provider fails.
- **Area:** Behavior.
- **Reason:** Provider errors are plain text. Reasoning/thinking is now in
  product scope (TUI v2 parity) but not implemented yet.
- **Next action:** Project and render reasoning parts in Session Chat.
- **Status:** Temporary (reasoning); provider error text is shipped. Tools have
  cards.
