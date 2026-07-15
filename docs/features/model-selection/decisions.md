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
  `SessionChatComposer`**, not `SessionChatViewModel` (shipped on Android and
  iOS).
- Reason: match OpenCode desktop session-local model memory without writing
  server config or adding global workspace defaults.
- Revisit when: agent selection becomes server-backed or cross-session defaults
  are explicitly in scope.
- Superseded in part on 2026-07-15: the New Session Model Preference is now in
  scope, connection identity adds effective username, and newer user history may
  override older session cache; composer ownership is unchanged.

## History Bootstrap Source

- Date: 2026-06-28
- Decision: When no cached selection exists, bootstrap from the latest **user**
  message with model metadata; fall back to latest assistant message if needed.
- Reason: align with desktop `local.session.restore(lastUserMessage)` behavior.
- Revisit when: API evidence shows user messages never carry model metadata for
  mobile clients.
- Extended on 2026-07-15: latest-user history also overrides an older or legacy
  session entry during cross-client reconciliation.

## Model Picker Uses Provider Drilldown

The composer model picker uses a two-step bottom sheet: Provider list, then
models for one Provider. If a Model Selection already exists, the sheet opens at
that Provider's models with the Provider name as the sheet title and a header
back button to the Provider list.

## Time-Aware Session Memory And New Session Model Preference

- Date: 2026-07-15
- Decision: Keep timestamped per-session selection and a separate **New Session
  Model Preference** scoped by normalized server URL, effective username, and
  exact server-provided worktree. Reconcile session cache with latest-user
  history by timestamp; newer history wins without changing the preference.
  Only explicit model/variant picks update the preference. Copy the resolved
  preference/catalog fallback into a session created from mobile Home before
  navigation. Serialize writes per scope and do not enable prompt send until
  initial selection resolution is complete.
- Reason: OpenCode web separates saved session state from workspace draft/recent
  state and promotes the effective draft selection into a created session.
  Mobile has no pre-creation composer, so the preference handoff preserves
  continuity without inventing a global cross-project default. Timestamp
  reconciliation deliberately improves cross-client correctness when web or
  another client has sent a newer user message.
- Revisit when: mobile gains a pre-creation composer or server-backed agent
  defaults, either of which would add a higher-priority source.
