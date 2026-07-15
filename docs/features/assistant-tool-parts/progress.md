# Assistant Tool Parts — Progress

## Shared Planning

- Status: task / child-session tool card (OPE-52) implemented on iOS and Android;
  file-change (OPE-51) remains next.
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
| [OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards) | File change tool cards | OPE-50 |
| [OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card) | Subagent task tool card | None (parallel with OPE-51) |

## Design System

- Tool Call Card shipped on iOS and Android with Component Gallery entries
  ([OPE-47](https://linear.app/opencode-mobile/issue/OPE-47/design-system-tool-call-card)).
  Contract: `docs/design-system/components/tool-call-card.md`.

## Android

- Structured `MessagePart.Tool` with `ToolStatus`, optional title/error/call id,
  opaque input/output/metadata; Android tool-state DTO has `metadata`.
- `Message.text` is TextPart-only; compact tool labels remain available via
  `compactToolLabel` for non-card surfaces.
- Session Chat timeline: context groups, shell/network detail sheet, interact
  chrome, and specialized `task` cards with child-session navigation
  ([OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome),
  [OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card)).
  File-change tools still use generic chrome until OPE-51.

## iOS

- Structured `ToolPart` with `ToolStatus`, optional title/error/call id, opaque
  input/output/metadata (`AnyCodable` maps).
- `Message.text` is TextPart-only; compact tool labels remain available via
  `compactToolLabel` for non-card surfaces.
- Session Chat timeline: context groups, shell/network detail sheet, interact
  chrome, and specialized `task` cards with child-session navigation
  ([OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome),
  [OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card)).
  File-change tools still use generic chrome until OPE-51.

## Remaining

- File-change tools (edit / write / apply_patch) ([OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards)) — grilled; unblocked.

## Completed

- Structured tool parts in the domain ([OPE-48](https://linear.app/opencode-mobile/issue/OPE-48/structured-tool-parts-in-the-domain)).
- Design-system Tool Call Card ([OPE-47](https://linear.app/opencode-mobile/issue/OPE-47/design-system-tool-call-card)).
- Generic tool cards in Session Chat (including hide rules) ([OPE-49](https://linear.app/opencode-mobile/issue/OPE-49/generic-tool-cards-in-session-chat)).
- Lightweight tool chrome (context, shell, network, interact) ([OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome)).
- Task / child-session tool card ([OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card)).
