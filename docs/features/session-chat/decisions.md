# Session Chat — Decisions

Still binding:

- `SessionChatViewModel` is a thin facade. Timeline, composer, and SessionInfo
  are regions.
- Timeline is oldest-to-newest. Pin to bottom when auto-follow is armed.
- Session Running chrome: show iff this session’s status is not idle.
  Optimistic busy on send is the same status. Do not inspect messages,
  streaming, tools, or thought rows. Place it as the last timeline row
  with a stable list identity; do not pin it to the composer. Labels
  from status only: `Working` (busy, including optimistic busy after
  send), `Retrying`, `Compacting…`. Never `Thinking` — that word stays
  on the thought row. Hide only when the permission or question dock is
  up; child lockout does not hide it if that session is still busy. The
  old hide-when-tools-run / hide-when-last-turn-has-in-progress-
  assistant-content rule stays revoked: it hid the lamp for the stretch
  TUI still shows busy (`session_status !== idle`).
  `WorkingTurnIndicator` collapses to status + compacting +
  awaiting-user. SessionInfo owns session status; the timeline owns
  last-row layout; the facade does not scan parts.
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
- Pending revert chrome is an attachment-style chip inside the composer,
  above the prompt: undo icon, title, `REVERT` kind, X to cancel. Do not
  mark the timeline, and do not use warning or highlight color on the
  composer shell. Send applies the revert. X cancels it.
- Streaming thought chrome is `Thinking` with the same pulsing status
  dot as Working. Finished thought is `Thought` / `Thought · Nms` with
  no dot. TUI only paints reasoning with display text (blank and
  `[REDACTED]`-only parts are omitted). Once text exists, the thought
  row is one stable item: keep it and only change the label. Do not
  drop or remount it between Thinking and Thought.
- Follow-up send is V2-only. Default is Steer. Settings **Follow-up
  send** chooses Steer or Queue. The composer send icon is Arrow Warm
  Up for Steer and Arrow Upward for Queue. Long-press send flips the
  next send and snacks `Switched to Steer` / `Switched to Queue`. Tap
  sends with the current mode. V1 has no delivery field, no long-press
  flip, and the setting is disabled (`Not supported on V1. This server
  always sends immediately.`).
- Waiting caption is feed-only and only until pickup. Copy: `Waiting
  to steer` / `Held until idle`, caption type, no badge. After
  `session.next.prompted` the row is a normal outgoing message.
