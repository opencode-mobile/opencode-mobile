# Session Chat — Decisions

Still binding:

- `SessionChatViewModel` is a thin facade. Timeline, composer, and SessionInfo
  are regions.
- Timeline is oldest-to-newest. Pin to bottom when auto-follow is armed.
- Working/Retrying comes from session status (SSE busy/retry), not from
  “any tool still open.” Custom Working and Thinking footer lamps are
  off for now (TUI has no transcript busy lamp). Retrying and
  Compacting… still show. Hide that chrome while a question or
  permission is waiting on the user — the dock is the wait state.
- Child sessions are read-only from the composer.
- Device attachments use Photos/Files. Project `file://` browse was not in
  the first attachment slice.
- Live frames may coalesce; required load / send completions must not be
  dropped.
- Context usage is derived from best available session + message data.
- Context tools (`read` / `glob` / `grep` / `websearch`) use TUI Explored
  chrome (`Explored — 1 read`, `Explored — 2 searches`). Read subtitle is
  `filePath`, never explore/cwd `path` (often the project root). Mixed
  non-context groups stay `N tools`.
- Streaming thought chrome is `Thinking` with the same pulsing status
  dot as Working. Finished thought is `Thought` / `Thought · Nms` with
  no dot. TUI only paints reasoning with display text (blank and
  `[REDACTED]`-only parts are omitted). Once text exists, the thought
  row is one stable item: keep it and only change the label. Do not
  drop or remount it between Thinking and Thought.
