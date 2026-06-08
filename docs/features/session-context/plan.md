# Session Context

## User Goal

Open details from the Session Chat context ring and inspect the session's model
context usage, message accounting, and raw message rows.

## In Scope

- Push a dedicated Session Context screen from the context ring in the session
  status bar on iOS and Android.
- Load an open-time snapshot and support pull-to-refresh.
- Show session title, message counts, provider/model labels, context limit,
  latest assistant token details, usage percent, cache read/write, total cost,
  created time, and last activity time.
- Show an approximate context breakdown for system, user, assistant, tool calls,
  and other input-token overhead.
- Show Raw messages as role, message id, and timestamp rows.

## Out Of Scope

- Live updates while the Session Context screen is open.
- Expandable JSON payloads for raw messages.
- Editing, pruning, compaction, or model/provider configuration.

## Server APIs Involved

- `GET /session/{id}`
- `GET /session/{id}/message`
- `GET /config/providers`

Calls should include `directory` when the repository method supports project
scoping.

## Domain Objects

- **Session Context** as the screen snapshot.
- `session` for title, model, cost, tokens, and session times.
- `message` for counts, raw rows, assistant costs, token details, and content
  estimate inputs.
- Model Selection catalog for provider/model labels and context limit.

## UI States

- Loading.
- Loaded snapshot.
- Pull-to-refresh while loaded.
- Error with retry.
- Empty breakdown when no assistant token usage is available.

## Design-System Components Needed

- Reuse `OpenCodeTopBar`, `OpenCodeSessionStatusBar`, and `OpenCodeButton`.
- Use feature-local stat rows, breakdown bar, and raw-message rows for this
  diagnostics layout.
