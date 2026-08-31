# Terminal — Decisions

Still binding:

- Talk to the server PTY API. Do not use `POST /session/{id}/shell`, the
  Session Chat bash sheet, or `/tui/*`.
- Terminal is scoped to an added project, not to a session. Session Chat
  `[terminal]` pushes the project shell; it does not embed a PTY in chat.
- Home Terminal icon opens a running-only catalog across added projects.
  Catalog does not create a PTY. Trailing close `DELETE`s that id.
- Leave and background disconnect I/O only. Do not `DELETE` on leave or
  background.
- Bundled xterm.js in a local WebView. Native markdown already rejected
  WebView for document layout; a VT emulator is a different job. Do not
  pull ghostty-web.
- Dual remotes. Feature UI stays on `TerminalSession`, never on wire
  schemas or a v1/v2 screen fork.
- Do not persist emulator scrollback in SwiftData or Room.
