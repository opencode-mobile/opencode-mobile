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
