# Assistant Tool Parts — Parity Gaps

## No rich tool cards in Session Chat yet

- **Intent:** Assistant tool calls render as native cards on both platforms.
- **iOS:** Flattened tool status lines inside message text; design-system
  `OpenCodeToolCallCard` exists in the Component Gallery but is not wired into
  Session Chat.
- **Android:** Flattened tool status lines inside message text; design-system
  `OpenCodeToolCallCard` exists in the Component Gallery but is not wired into
  Session Chat.
- **Area:** Missing feature wiring (OPE-49+).
- **Reason:** Tool Call Card foundation shipped (OPE-47); generic Session Chat
  cards remain.
- **Next action:** Implement [OPE-49](https://linear.app/opencode-mobile/issue/OPE-49/generic-tool-cards-in-session-chat).
- **Status:** Temporary.
