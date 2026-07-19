# Slash & @ Commands

Linear: [OPE-56 — Slash & @ Commands](https://linear.app/opencode-mobile/issue/OPE-56/slash-and-commands)  
Active ticket: [OPE-57 — Server slash commands](https://linear.app/opencode-mobile/issue/OPE-57/server-slash-commands)

## User Goal

Discover and run **server commands** from the Session Chat composer with `/`,
matching opencode web/desktop server contracts. Later tickets add client slash
actions and agent `@` mentions.

## In Scope (OPE-57)

- List server commands via `GET /command` (project `directory`).
- Composer `/` suggestions (bare leading token only) in a composer-anchored
  overlay; select inserts `/name ` for optional arguments.
- Full server catalog including skills; row shows name, description, skill/mcp
  badge.
- Send matching `/name args` via `POST /session/{id}/command` with composer
  snapshot (agent/model/variant + attachment `parts`); omit `messageID`.
- Unknown `/…` and normal text still use prompt send.
- Command Repository; design-system suggestion overlay reused later by OPE-58/59.
- Prefetch on Session Chat enter / reconnect; retry when `/` invoked; quiet
  empty if catalog fails.
- iOS and Android parity with unit coverage for list/match/submit routing.

## Out Of Scope

- Client slash actions ([OPE-58](https://linear.app/opencode-mobile/issue/OPE-58/slash-actions)).
- `@` mentions ([OPE-59](https://linear.app/opencode-mobile/issue/OPE-59/mention-action)).
- Shell mode (`!`), separate skills dialog, desktop slash-menu-when-text-present.
- Argument-hints UI from command `hints`.

Research: [slash-and-at-commands.md](../../research/slash-and-at-commands.md).

## Server APIs Involved

- `GET /command?directory=` — list **server commands**.
- `POST /session/{sessionID}/command?directory=` — run a server command
  (`command`, `arguments` required; optional `agent`, `model` as
  `provider/model`, `variant`, `parts`).

## Domain Objects

- **server command** — see `CONTEXT.md`.
- **Command Repository** — list + run.
- Composer submit routing: server command vs prompt.

## Screen Model

- Catalog loaded into Session Chat; slash suggestion query from composer text.
- Actions: open/filter/dismiss suggestions, select insert, send (routed).

## Native Entry Points

- Session Chat composer on iOS and Android.

## Error States

- Catalog failure: quiet empty suggestions; retry on `/` / reconnect.
- Command send failure: restore text + attachments like prompt send.

## Design-System Components

- **New:** composer suggestion overlay (anchored above composer).
- **Existing:** list item / choice badge for rows and source labels.
