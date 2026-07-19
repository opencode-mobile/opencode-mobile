# OPE-59 — Agent @ mentions

Linear: [OPE-59 — Agent @ mentions](https://linear.app/opencode-mobile/issue/OPE-59/mention-action)  
Parent: [OPE-56 — Slash & @ Commands](https://linear.app/opencode-mobile/issue/OPE-56/slash-and-commands)  
Feature: [plan.md](./plan.md)

## User Goal

In Session Chat, type `@` to mention a **mentionable agent**. Selection inserts
an inline `@name` in the composer (web-parity colored text). Send includes a
structured agent part on the prompt so the server can route task-tool work.

## In Scope

- List mentionable agents via Agent Repository (`GET /agent`, project
  `directory`): non-primary and not hidden.
- Composer `@` trigger (after start or whitespace; no whitespace between `@` and
  cursor); filter token after `@`.
- `/` and `@` suggestion modes mutually exclusive; `@` wins when both match.
- Reuse design-system composer suggestion overlay; rows show `@name` and
  description when present.
- Select inserts inline `@name` styled with `syntax-type` (web
  `--color-syntax-type`); structured mention state mapped to that span; atomic
  delete of the whole token; multiple mentions allowed.
- Prompt send includes structured `agent` parts for selected mentions plus
  `@name` in the text part (web parity).
- Prefetch agents on Session Chat enter / reconnect; retry when `@` opens; quiet
  empty if list fails.
- iOS and Android with unit coverage for trigger/filter, insert/delete, and
  prompt vs command send routing.

## Out Of Scope

- Project file, MCP resource, and reference `@` mentions (documented parity gap).
- Composer mode-picker sync to the agent catalog (follow-up).
- Client slash actions ([OPE-58](https://linear.app/opencode-mobile/issue/OPE-58/slash-actions)).
- Sent-message mention chrome (no attachment-style rows for agent parts).
- Shell mode (`!`).
- Auto-promoting typed `@name` to an agent part without selecting a suggestion.

## Server APIs Involved

- `GET /agent?directory=` — list agents for the mention catalog.
- Existing async prompt send — include `agent` parts for selected mentions.

## Domain Objects

- **mentionable agent**, **agent mention**, **Agent Repository** — see
  `CONTEXT.md`.
- Composer submit: mentions ride on prompt send only.

## Screen Model

- Agent catalog in Session Chat; `@` suggestion query from composer text before
  cursor.
- Pending agent mentions as structured composer state + inline styled spans.
- Actions: open/filter/dismiss `@` suggestions, select insert, atomic delete,
  send (prompt includes agent parts; server-command send drops them).

## Native Entry Points

- Session Chat composer on iOS and Android.

## Error States

- Catalog failure: quiet empty suggestions; retry on `@` / reconnect.
- Prompt send failure: restore text, attachments, and mention state like today.
- Server-command send with pending mentions: send proceeds; mention parts are
  not attached to `session.command` (parts remain file attachments only).

## Design-System Components

- **Existing:** composer suggestion overlay; `syntax-type` for inline mention
  color.
- **Extended:** Session Chat composer text field must support attributed /
  annotated spans for mention ranges (feature wiring; no attachment chip).
- **Not used:** `OpenCodeAttachmentChip` for agent mentions.

## Expected Platform Logs

- Agent list fetch / retry failures (same quiet posture as command catalog).
- Prompt send including agent part count when mentions are present.
