# Assistant Tool Parts — Progress

## Shared Planning

- Status: planned; OPE-48 domain enrichment shipped on both platforms; UI tickets remain.
- Linear: [OPE-46](https://linear.app/opencode-mobile/issue/OPE-46/assistant-tool-parts)
- Docs: `docs/features/assistant-tool-parts/`
- Research: `docs/research/opencode-assistant-tool-messages.md`

### Child tickets

| Ticket | Title | Blocked by |
| --- | --- | --- |
| [OPE-48](https://linear.app/opencode-mobile/issue/OPE-48/structured-tool-parts-in-the-domain) | Structured tool parts in the domain | None |
| [OPE-47](https://linear.app/opencode-mobile/issue/OPE-47/design-system-tool-call-card) | Design-system Tool Call Card | None |
| [OPE-49](https://linear.app/opencode-mobile/issue/OPE-49/generic-tool-cards-in-session-chat) | Generic tool cards in Session Chat | OPE-47, OPE-48 |
| [OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome) | Lightweight tool chrome | OPE-49 |
| [OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards) | File change tool cards | OPE-49 |
| [OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card) | Subagent task tool card | OPE-49 |

## Design System

- Tool Call Card not started.

## Android

- Structured `MessagePart.Tool` with `ToolStatus`, optional title/error/call id,
  opaque input/output/metadata; Android tool-state DTO has `metadata`.
- Compact emoji labels come from `MessageDomainAdapter.compactToolLabel`;
  `Message.text` still joins text + formatter labels (no card UI yet).

## iOS

- Structured `ToolPart` with `ToolStatus`, optional title/error/call id, opaque
  input/output/metadata (`AnyCodable` maps).
- Compact emoji labels come from `MessageDomainAdapter.compactToolLabel`;
  `Message.text` still joins text + formatter labels (no card UI yet).

## Remaining

- Design-system Tool Call Card ([OPE-47](https://linear.app/opencode-mobile/issue/OPE-47/design-system-tool-call-card)).
- Generic tool cards in Session Chat (including hide rules) ([OPE-49](https://linear.app/opencode-mobile/issue/OPE-49/generic-tool-cards-in-session-chat)).
- Lightweight tool chrome (context, shell, network, interact) ([OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome)).
- File change tools (edit / write / apply_patch) ([OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards)).
- Subagent task tool card ([OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card)).

## Completed

- Structured tool parts in the domain ([OPE-48](https://linear.app/opencode-mobile/issue/OPE-48/structured-tool-parts-in-the-domain)).
