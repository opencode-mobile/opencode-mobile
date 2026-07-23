# Slash & @ Commands

Linear: [OPE-56 — Slash & @ Commands](https://linear.app/opencode-mobile/issue/OPE-56/slash-and-commands)  
Active ticket: [OPE-58 — Slash actions](https://linear.app/opencode-mobile/issue/OPE-58/slash-actions)

## User Goal

Discover and run **server commands** from the Session Chat composer with `/`,
mention **mentionable agents** with `@`, and run curated **client slash
actions** from a Commands sheet — matching opencode web/desktop contracts where
mobile v1 allows.

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

## Shipped (OPE-59)

See [ope-59-plan.md](./ope-59-plan.md). Summary:

- `@` opens mentionable-agent suggestions (non-primary, not hidden).
- Select inserts inline `@name` (`syntax-type` color) with structured mention
  state; prompt send includes `agent` parts.
- `/` and `@` mutually exclusive (`@` wins); file/MCP/reference `@` deferred.

## In Scope (OPE-58)

See [ope-58-plan.md](./ope-58-plan.md). Summary:

- Composer Commands `/` button next to Attach opens a **Commands** bottom sheet.
- Curated client actions: `new`, `fork`, `undo`, `redo`, `compact` (hide when
  inapplicable; never mixed into typed `/` suggestions).
- Select runs local session behavior immediately; not `POST …/command`.

## Out Of Scope (feature)

- Project file, MCP resource, and reference `@` mentions.
- Shell mode (`!`), separate skills dialog, desktop slash-menu-when-text-present.
- Argument-hints UI from command `hints`.
- Composer mode-picker sync to `GET /agent`.
- Client `share` / `unshare` slash actions.
- Desktop-only client builtins (terminal, themes, exit, MCP admin, …).

Research: [slash-and-at-commands.md](../../research/slash-and-at-commands.md).

## Server APIs Involved

- `GET /command?directory=` — list **server commands**.
- `POST /session/{sessionID}/command?directory=` — run a server command.
- `GET /agent?directory=` — list agents for **mentionable agent** catalog
  (OPE-59).
- Async prompt send — `agent` parts for selected **agent mentions** (OPE-59).
- Session create / fork / revert / unrevert / abort / summarize — **client slash
  actions** (OPE-58); see [ope-58-plan.md](./ope-58-plan.md).

## Domain Objects

- **server command**, **Command Repository** — see `CONTEXT.md`.
- **mentionable agent**, **agent mention**, **Agent Repository** — see
  `CONTEXT.md` (OPE-59).
- **client slash action** — see `CONTEXT.md` (OPE-58).

## Screen Model

- Catalogs loaded into Session Chat; `/` and `@` suggestion queries from
  composer text.
- Commands sheet visibility and visible client-action rows (OPE-58).
- Actions: open/filter/dismiss suggestions, select insert, send (routed), open
  Commands and run client actions.

## Native Entry Points

- Session Chat composer on iOS and Android.

## Error States

- Catalog failure: quiet empty suggestions; retry on trigger / reconnect.
- Command or prompt send failure: restore composer snapshot (text, attachments,
  mentions).
- Client action failure: snackbar or dialog error; see [ope-58-plan.md](./ope-58-plan.md).

## Design-System Components

- **Existing:** composer suggestion overlay; list/badge chrome for `/` rows;
  `syntax-type` for inline `@` mention color; bottom sheet action list.
- **OPE-59:** attributed composer spans for mention ranges (not attachment
  chips).
- **OPE-58:** extend Session Chat composer with Commands `IconCircle` after
  Attach.
