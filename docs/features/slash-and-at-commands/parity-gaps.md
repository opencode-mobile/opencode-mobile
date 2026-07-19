# Slash & @ Commands — Parity Gaps

## OPE-57

No intentional or temporary platform differences recorded for OPE-57.

## OPE-59

### `@` catalog is agents-only on mobile

- **Shared intent:** Web `@` suggestions mix references, agents, MCP resources,
  and project files (`find.files`). Mobile OPE-59 ships **mentionable agents**
  only under the same `@` trigger and suggestion overlay.
- **iOS / Android:** Agents-only catalog; quiet empty when none match.
- **Reason:** Project file / MCP / reference mentions need APIs and product
  surfaces outside v1 attachments scope; agent mentions are feasible with
  `GET /agent` alone.
- **Intentional** until a later ticket adds file/MCP/reference `@`.
- **Next action:** Keep deferred in feature out-of-scope; do not fake file rows.
