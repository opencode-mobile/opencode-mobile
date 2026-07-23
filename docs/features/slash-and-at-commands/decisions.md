# Slash & @ Commands — Decisions

## OPE-57

- Product name is **server command**; `/` is the composer trigger only.
- Select inserts `/name `; send routes when the head token matches the catalog.
- Unknown `/foo` falls through to normal prompt send.
- **Command Repository** owns list + run (not Message/Session Repository).
- Command send copies the composer snapshot: agent/model/variant and pending
  attachments as `parts`; no `messageID`.
- Suggestions open only for bare `/` + optional filter token; close after space.
- Full `GET /command` catalog including skills; badges for skill/mcp; no hints UI.
- Prefetch on Session Chat enter / reconnect; retry when `/` is invoked.
- Composer-anchored suggestion overlay is design-system chrome (for OPE-58/`@`).

## OPE-59

- Product name is **agent mention**; `@` is the composer trigger only. It does
  not switch the composer's selected agent/mode.
- Catalog is **mentionable agents** only: server agents whose mode is not
  `primary` and who are not `hidden`. File, MCP resource, and reference `@`
  options stay out of this ticket (parity gap vs web).
- Trigger follows TUI rules: `@` after start or whitespace; no whitespace
  between `@` and cursor; `@` wins over `/` when both could apply.
- Select inserts inline `@name` styled with `syntax-type` (web
  `--color-syntax-type`), not an attachment chip. Structured mention state maps
  to that span; the token deletes atomically; multiple mentions are allowed.
- Structured `agent` parts are created only from explicit suggestion selection.
  Typed `@…` without selecting stays plain text.
- Mentions ride on **prompt** send only. Server-command send drops mention
  parts (command `parts` remain file attachments).
- No sent-message mention chrome; `@name` remains in message text.
- **Agent Repository** lists agents for the mention catalog. Prefetch / retry /
  quiet-empty match the command catalog posture. Mode-picker sync to the same
  catalog is a follow-up, not OPE-59.
- Implementation order: OPE-59 before OPE-58 (both unblocked after OPE-57).

## OPE-58

- Product name is **client slash action**; entry is a composer **Commands**
  button (`/`), not the typed `/` overlay.
- Typed `/` stays **server commands** only (OPE-57, including skills/MCP).
  Client actions do not appear in `/` suggestions.
- Composer layout: `[+] [/] … [send]`; Commands is a design-system
  `OpenCodeSessionComposer` action.
- Sheet title **Commands**; rows are one line: `[command] optional description`.
- Allowlist: `new`, `fork`, `undo`, `redo`, `compact`. Hide inapplicable rows;
  `new` is always shown. No `share`/`unshare`; no desktop-only builtins; no
  `model`/`agent` rows.
- Select runs immediately after dismiss; do not clear the composer draft except
  undo/redo restore semantics. Never `POST …/command`.
- Opening Commands dismisses `/` and `@` suggestion overlays; draft text stays.
- `new` creates in the current worktree, seeds model preference, navigates with
  replace.
- `fork` uses the OPE-81 whole-session flow (name dialog → Open/Stay). OPE-58
  implements that path; OPE-81 wires overflow and message fork later.
- `undo`/`redo` match web session revert/unrevert (abort if busy; restore
  composer on undo). Message-sheet “Revert message” copy stays for a follow-up.
- `compact` is a thin summarize call with the selected composer model; richer
  compact UX remains OPE-80.
