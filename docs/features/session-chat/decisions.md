# Session Chat — Decisions

Still binding:

- `SessionChatViewModel` is a thin facade. Timeline, composer, and SessionInfo
  are regions.
- Timeline is oldest-to-newest. Pin to bottom when auto-follow is armed.
- Working/Retrying comes from session status (SSE busy/retry), not from
  “any tool still open.” Hide that chrome while a question or permission
  is waiting on the user — the dock is the wait state (TUI shows the
  question, not a second Thinking line).
- Child sessions are read-only from the composer.
- Device attachments use Photos/Files. Project `file://` browse was not in
  the first attachment slice.
- Live frames may coalesce; required load / send completions must not be
  dropped.
- Context usage is derived from best available session + message data.
