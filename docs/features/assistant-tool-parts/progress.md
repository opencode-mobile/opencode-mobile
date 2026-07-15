# Assistant Tool Parts — Progress

## Shared Planning

- Status: lightweight tool chrome (OPE-50) in progress on iOS and Android;
  file-change (OPE-51) and task (OPE-52) grilled, blocked on OPE-50.
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
| [OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card) | Subagent task tool card | OPE-50 |

## Design System

- Tool Call Card shipped on iOS and Android with Component Gallery entries
  ([OPE-47](https://linear.app/opencode-mobile/issue/OPE-47/design-system-tool-call-card)).
  Contract: `docs/design-system/components/tool-call-card.md`.

## Android

- Structured `MessagePart.Tool` with `ToolStatus`, optional title/error/call id,
  opaque input/output/metadata; Android tool-state DTO has `metadata`.
- `Message.text` is TextPart-only; compact tool labels remain available via
  `compactToolLabel` for non-card surfaces.
- Session Chat timeline blocks WIP for OPE-50: context tool groups,
  shell/network Bottom Sheet detail, interact copy; generic cards remain for
  file-change and `task` ([OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome)).

## iOS

- Structured `ToolPart` with `ToolStatus`, optional title/error/call id, opaque
  input/output/metadata (`AnyCodable` maps).
- `Message.text` is TextPart-only; compact tool labels remain available via
  `compactToolLabel` for non-card surfaces.
- Session Chat timeline blocks WIP for OPE-50: context tool groups,
  shell/network Bottom Sheet detail, interact copy; generic cards remain for
  file-change and `task` ([OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome)).

## Remaining

- Lightweight tool chrome (context, shell, network, interact) ([OPE-50](https://linear.app/opencode-mobile/issue/OPE-50/lightweight-tool-chrome)) — WIP on `feature/ope-50-lightweight-tool-chrome`.
- File-change tools (edit / write / apply_patch) ([OPE-51](https://linear.app/opencode-mobile/issue/OPE-51/file-change-tool-cards)) — grilled; blocked by OPE-50.
- Subagent task tool card ([OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card)) — grilled; parallel after OPE-50 patterns land.

## Completed

- Structured tool parts in the domain ([OPE-48](https://linear.app/opencode-mobile/issue/OPE-48/structured-tool-parts-in-the-domain)).
- Design-system Tool Call Card ([OPE-47](https://linear.app/opencode-mobile/issue/OPE-47/design-system-tool-call-card)).
- Generic tool cards in Session Chat (including hide rules) ([OPE-49](https://linear.app/opencode-mobile/issue/OPE-49/generic-tool-cards-in-session-chat)).
