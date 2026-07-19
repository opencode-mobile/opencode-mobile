# Slash & @ Commands

Linear: [OPE-56 — Slash & @ Commands](https://linear.app/opencode-mobile/issue/OPE-56/slash-and-commands)  
Active ticket: [OPE-59 — Agent @ mentions](https://linear.app/opencode-mobile/issue/OPE-59/mention-action)

## User Goal

Discover and run **server commands** from the Session Chat composer with `/`,
then mention **mentionable agents** with `@`, matching opencode web/desktop
contracts where mobile v1 allows. Client slash actions follow later.

## Shipped (OPE-57)

- List server commands via `GET /command` (project `directory`).
- Composer `/` suggestions (bare leading token only) in a composer-anchored
  overlay; select inserts `/name ` for optional arguments.
- Full server catalog including skills; row shows name, description, skill/mcp
  badge.
- Send matching `/name args` via `POST /session/{id}/command` with composer
  snapshot (agent/model/variant + attachment `parts`); omit `messageID`.
- Unknown `/…` and normal text still use prompt send.
- Command Repository; design-system suggestion overlay.
- Prefetch on Session Chat enter / reconnect; retry when `/` invoked; quiet
  empty if catalog fails.
- iOS and Android parity with unit coverage for list/match/submit routing.

## In Scope (OPE-59)

See [ope-59-plan.md](./ope-59-plan.md). Summary:

- `@` opens mentionable-agent suggestions (non-primary, not hidden).
- Select inserts inline `@name` (`syntax-type` color) with structured mention
  state; prompt send includes `agent` parts.
- `/` and `@` mutually exclusive (`@` wins); file/MCP/reference `@` deferred.

## Later (OPE-58)

- Curated client slash actions alongside server commands under `/`.

## Out Of Scope (feature)

- Project file, MCP resource, and reference `@` mentions.
- Shell mode (`!`), separate skills dialog, desktop slash-menu-when-text-present.
- Argument-hints UI from command `hints`.
- Composer mode-picker sync to `GET /agent` (not required for OPE-59).

Research: [slash-and-at-commands.md](../../research/slash-and-at-commands.md).

## Server APIs Involved

- `GET /command?directory=` — list **server commands**.
- `POST /session/{sessionID}/command?directory=` — run a server command.
- `GET /agent?directory=` — list agents for **mentionable agent** catalog
  (OPE-59).
- Async prompt send — `agent` parts for selected **agent mentions** (OPE-59).

## Domain Objects

- **server command**, **Command Repository** — see `CONTEXT.md`.
- **mentionable agent**, **agent mention**, **Agent Repository** — see
  `CONTEXT.md` (OPE-59).

## Screen Model

- Catalogs loaded into Session Chat; `/` and `@` suggestion queries from
  composer text.
- Actions: open/filter/dismiss suggestions, select insert, send (routed).

## Native Entry Points

- Session Chat composer on iOS and Android.

## Error States

- Catalog failure: quiet empty suggestions; retry on trigger / reconnect.
- Command or prompt send failure: restore composer snapshot (text, attachments,
  mentions).

## Design-System Components

- **Existing:** composer suggestion overlay; list/badge chrome for `/` rows;
  `syntax-type` for inline `@` mention color.
- **OPE-59:** attributed composer spans for mention ranges (not attachment
  chips).
