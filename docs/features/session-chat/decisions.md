# Session Chat — Decisions

Still binding:

- `SessionChatViewModel` is a thin facade. Timeline, composer, and SessionInfo
  are regions.
- Timeline is oldest-to-newest. Pin to bottom when auto-follow is armed.
- Session Running chrome: show iff this session’s status is not idle.
  Optimistic busy on send is the same status. Place it as the last
  timeline row with a stable list identity; do not pin it to the
  composer. Labels from status only: `Working` (busy, including
  optimistic busy after send), `Retrying`, `Compacting…`. Never
  `Thinking` — that word stays on the thought row. Hide when the
  permission or question dock is up, or when the last painted row is
  Thinking (that row already occupies the bottom). Do not hide for
  settled Thought, tools, or streaming text. Child lockout does not
  hide it if that session is still busy. The old hide-when-tools-run /
  hide-when-last-turn-has-in-progress-assistant-content rule stays
  revoked except for last-row Thinking.
  `WorkingTurnIndicator` collapses to status + send progress + compacting +
  awaiting-user + last-is-thinking. SessionInfo owns session status;
  the timeline owns last-row layout. Optimistic busy is write-through
  to `SessionStatusLiveStore` so home Running and chat Working share
  one map. Send-fail calls `clearLocalBusy`, which drops only the
  local overlay so REST/SSE busy stays. Abort calls `markIdle` so
  Working hides without waiting for SSE.
- Child sessions are read-only from the composer.
- Device attachments use Photos/Files. Project `file://` browse was not in
  the first attachment slice. Pending Attachment bytes stay in an app-owned
  file. Pick rejects more than 20 MiB decoded. The prompt still sends a
  `data:` URL; that string is streamed from the file. Optimistic FilePart
  and session cache do not persist `data:` bodies.
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
- Commands sheet is a two-column `OpenCodeActionTile` grid inside
  `OpenCodeBottomSheet`. Each tile is catalog icon plus short label
  (New, Terminal, Fork, Undo, Redo, Compact). No `/cmd` subtitle.
  Accessibility uses the full action description. Icons: `add`,
  `terminal`, `call_split`, `undo`, `redo`, `vertical_align_center`.
