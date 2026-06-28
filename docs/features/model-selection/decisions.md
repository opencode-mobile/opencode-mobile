# Model Selection — Decisions

## Variant Is The Server-Backed Selector

The mobile composer uses **Variant** for the server-provided model variant keys.
`Default` is a mobile label only and is omitted from prompt requests.

## Persistent Per-Session Cache

- Date: 2026-06-28
- Decision: Persist composer model/variant locally per
  `server + worktree + sessionId`. Restore cached selection before history
  bootstrap or catalog defaults. Write cache on explicit user model/variant
  changes and successful prompt send. **Store I/O lives in
  `SessionChatComposer`**, not `SessionChatViewModel` (Android shipped;
  iOS parity pending).
- Reason: match OpenCode desktop session-local model memory without writing
  server config or adding global workspace defaults.
- Revisit when: agent selection becomes server-backed or cross-session defaults
  are explicitly in scope.

## History Bootstrap Source

- Date: 2026-06-28
- Decision: When no cached selection exists, bootstrap from the latest **user**
  message with model metadata; fall back to latest assistant message if needed.
- Reason: align with desktop `local.session.restore(lastUserMessage)` behavior.
- Revisit when: API evidence shows user messages never carry model metadata for
  mobile clients.

## Model Picker Uses Provider Drilldown

The composer model picker uses a two-step bottom sheet: Provider list, then
models for one Provider. If a Model Selection already exists, the sheet opens at
that Provider's models with the Provider name as the sheet title and a header
back button to the Provider list.
