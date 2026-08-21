# Session Chat — Decisions

Still binding:

- `SessionChatViewModel` is a thin facade. Timeline, composer, and SessionInfo
  are regions.
- Timeline is oldest-to-newest. Pin to bottom when auto-follow is armed.
- Session Running accessory (composer busy accessory): show iff this
  session’s status is not idle. Optimistic busy on send is the same
  status. Do not inspect messages, streaming, tools, or thought rows.
  Pin it above the composer, left-aligned; do not put it in the
  timeline. Labels from status only: `Working` (busy, including
  optimistic busy after send), `Retrying`, `Compacting…`. Never
  `Thinking` — that word stays on the thought row. Hide only when the
  permission or question dock is up; child lockout does not hide it if
  that session is still busy. The old hide-when-tools-run / hide-when-
  last-turn-has-in-progress-assistant-content rule is revoked: it hid
  the lamp for the stretch TUI still shows busy (`session_status !==
  idle`). `WorkingTurnIndicator` collapses to status + compacting +
  awaiting-user. SessionInfo owns session status; the composer region
  owns accessory layout; the facade does not scan parts. The timeline
  status-indicator slot is retired.
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
