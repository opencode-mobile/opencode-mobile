# OPE-58 — Slash actions (Commands sheet)

Linear: [OPE-58 — Slash actions](https://linear.app/opencode-mobile/issue/OPE-58/slash-actions)  
Parent: [OPE-56 — Slash & @ Commands](https://linear.app/opencode-mobile/issue/OPE-56/slash-and-commands)  
Feature: [plan.md](./plan.md)

## User Goal

From Session Chat, open a **Commands** bottom sheet from a `/` circle button
next to Attach and run curated **client slash actions** (local session
behaviors). Typed `/` remains the path for **server commands** (OPE-57).

## In Scope

- Extend `OpenCodeSessionComposer` prompt row to `[+] [/] … [send]` with a
  Commands circle action using text `/` (`iconCircleText` /
  `IconCircleText` on the design-system button).
- `/` opens `OpenCodeBottomSheet` titled **Commands** with one-line rows:
  `[command] optional description`.
- Allowlist (hide inapplicable rows; `new` always visible):

  | Command | Copy | When visible |
  | --- | --- | --- |
  | `new` | `[new] Start a new session` | Always |
  | `fork` | `[fork] Fork this session` | ≥1 visible user message |
  | `undo` | `[undo] Undo last message` | Undoable user message exists |
  | `redo` | `[redo] Redo undone message` | Active session revert |
  | `compact` | `[compact] Compact session context` | ≥1 visible user message and selected composer model |

- Tap row → dismiss sheet → run immediately. Do not clear the composer draft
  (except undo/redo’s own composer restore). Never submit as
  `POST …/command`.
- Opening Commands dismisses any open `/` or `@` suggestion overlay; draft text
  stays.
- **`new`:** create session in the current Session Chat worktree, seed model
  preference like home, navigate with **replace** to the new session.
- **`fork`:** implement the OPE-81 whole-session fork product path (name dialog
  → fork → Open / Stay). OPE-58 owns that flow; OPE-81 later wires overflow and
  message “Fork from here” to the same path.
- **`undo` / `redo`:** web session semantics — abort if busy, then
  `revert` / `unrevert`, restore composer from reverted user parts on undo.
  Do not rename message-sheet “Revert message” in this ticket.
- **`compact`:** thin `POST …/summarize` with the selected composer model;
  success/error snackbar (`OpenCodeSnackbar`); cheap message/session refresh
  if already cheap. Richer compact UX stays
  [OPE-80](https://linear.app/opencode-mobile/issue/OPE-80/session-compact).
- iOS and Android unit coverage for allowlist visibility and action routing
  (client action vs server-command submit).

## Out Of Scope

- Client actions in the typed `/` suggestion overlay (typed `/` stays OPE-57
  server catalog, including skills/MCP).
- `share` / `unshare`.
- Desktop/TUI-only builtins (`terminal`, `open`, `themes`, `exit`, MCP admin,
  workspaces, …).
- `model` / `agent` slash rows (composer chips already cover selection).
- Full compact progress/timeline UX (OPE-80).
- Renaming message “Revert message” (restore-to-composer only today).
- Overflow / message fork entry points (OPE-81 follow-through).

## Server APIs Involved

- `POST /session` — create for `/new`.
- `POST /session/{id}/fork` — whole-session fork (`messageID` omitted).
- `PATCH /session/{id}` — set fork title after create (OPE-81 path).
- `POST /session/{id}/revert`, `POST /session/{id}/unrevert` — undo / redo.
- `POST /session/{id}/abort` — used when undoing while busy.
- `POST /session/{id}/summarize` — thin compact.

## Domain Objects

- **client slash action** — curated local Commands-sheet action; not a server
  command. See `CONTEXT.md`.
- **session** — must expose revert state needed for redo visibility and undo
  targeting (map from server session `revert` if not already on the domain
  model).
- Existing Session Repository create / fork / revert / unrevert / summarize /
  abort.

## Screen Model

- Commands sheet visibility; computed visible action rows from session +
  composer state.
- Actions: open/dismiss Commands; run `new` / `fork` / `undo` / `redo` /
  `compact`; dismiss suggestion overlays when Commands opens.

## Native Entry Points

- Session Chat composer Commands button on iOS and Android.

## Error States

- Create / fork / revert / summarize failure: stay on current session (except
  successful `new`/`fork` navigation paths); show clear error snackbar
  (`OpenCodeSnackbar`) or dialog error (fork name dialog stays open on fork
  failure per OPE-81).
- Missing model for compact: row hidden (not toasted).
- Transient Session Chat failures (Commands, send, abort, archive/delete, …)
  use auto-dismissing snackbars; the timeline full-screen load-error empty
  state remains a page state, not a snackbar.

## Design-System Components

- **Extended:** `OpenCodeSessionComposer` — Commands circle after Attach with
  text `/` via `iconCircleText` / `IconCircleText`.
- **Existing:** `OpenCodeBottomSheet` / `OpenCodeBottomSheetEntry` (title-only
  one-line rows); `OpenCodeDialog` + text field for fork name (OPE-81 path);
  `OpenCodeSnackbar` for transient success/error feedback.
- **Button variant:** `iconCircleText` / `IconCircleText` for the `/` glyph.

## Expected Platform Logs

- Commands sheet open; each action start/success/failure with session id.
- Summarize / revert / fork / create failures with error message.
