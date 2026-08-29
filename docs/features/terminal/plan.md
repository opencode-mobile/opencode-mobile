# Terminal

## Now

Home Terminal action, dual remotes, `ensureRunning`, bundled xterm.js WebView
with native WebSocket I/O, control strip, and reconnecting after a dropped
socket.

## Remaining

Connect-token HTTP when the server requires `x-opencode-ticket`. Hardware
keyboard shortcuts.

## User Goal

From home, open a live shell on the connected opencode server for the selected
added project, type commands, and see output, without leaving the app for SSH.

## In Scope

- One Terminal screen on iOS and Android, scoped to the selected added project
  (`directory=`), not to a session.
- Home top-bar action (icon left of Settings) when home is loaded. Tap with a
  specific project selected opens Terminal. Tap with All selected snacks
  `Select a project first.` and stays on home. Hide the action when home is
  not loaded or there are no added projects.
- Reuse a running server PTY for that worktree if one exists. Otherwise create
  one with the server default shell and that worktree as cwd.
- Foreground WebSocket to `/pty/{id}/connect` (v2: `/api/pty/{id}/connect`).
  Close the socket on background. Do not delete the PTY when leaving the
  screen or backgrounding. Reopen reconnects if it is still running.
- Dual remotes. Same Screen Model on v1 and v2.
- Bundled xterm.js in a local WebView. Map theme tokens into the emulator
  inside the host. Do not load remote JS.
- A feature-local control strip: Ctrl, Esc, Tab, arrows. Mobile keyboards do
  not send those reliably.
- Product errors: connect failed, process exited, project missing.

## Out Of Scope

- `POST /session/{id}/shell` and the Session Chat bash sheet. Those are
  agent tool output, not a terminal.
- `/tui/*`. That drives a desktop TUI that is already running.
- Session Chat embedding, split view, or a tab inside chat.
- Multiple terminals, a PTY list UI, or renaming.
- Local scrollback persistence. The server process is the source of truth.
- SSH, mosh, or a shell on the phone.
- ghostty-web / WASM, SwiftTerm, or a from-scratch ANSI parser in this slice.
- A custom command at create time. Use the server default shell.
- Background I/O, notification of process output, or keeping the socket alive
  after the 5s background grace used by SSE.
- Hardware-keyboard shortcuts, tmux UX, or clickable `file://` links.

## Server APIs Involved

v1, all with `directory={worktree}`:

- `GET /pty`
- `POST /pty`
- `GET /pty/{id}`
- `PUT /pty/{id}` body `{ size: { rows, cols } }`
- `GET /pty/{id}/connect` upgraded to WebSocket
- connect ticket when the server requires `x-opencode-ticket` (same as
  official web)

v2 equivalents: `/api/pty` and `/api/pty/{id}/connect` with
`location[directory]`.

WebSocket (match official web):

- `ws` / `wss` from the Connection Profile base URL
- query `cursor`, plus v1 `directory` or v2 `location[directory]`
- v1 may pass `auth_token` or `ticket`; never log those
- `binaryType = arraybuffer`
- string frames are PTY output, written to the emulator
- binary frames with first byte `0` are JSON control (`cursor`); ignore other
  binary
- emulator input is `WebSocket.send` of text
- on resize, `PUT`/`pty.update` with cols/rows, debounced ~100ms

SSE `pty.created` / `pty.updated` / `pty.exited` / `pty.deleted` may update
the screen when the foreground event stream is already up. Do not add a
second SSE client. REST `GET /pty/{id}` after an abnormal socket close is
enough to detect exited.

## Domain Objects

- `TerminalSession` product type: `id`, `title`, `command`, `cwd`, `status`
  (`running` | `exited`). Wire name PTY stays in remotes.
- Selected added project worktree, passed from home. Terminal does not pick
  a project itself.
- `TerminalRepository`: list, create, get, update size, connect, no delete
  in this slice.

## Screen Model structure

`TerminalViewModel` is one region. Immutable UI state plus explicit actions.
It does not live under `SessionChatViewModel`.

Actions: `appear`, `disappear`, `retry`, `foreground`, `background`,
`resize(cols, rows)`. Byte I/O stays in the WebView host, not in UI state.

## UI States

- Connecting
- Live
- Reconnecting (socket dropped, process still running)
- Exited (process ended; primary Retry creates a new PTY)
- Connect failed

Chrome: `OpenCodeTopBar` title `Terminal`, subtitle the project label with
letter badge, leading back. Control strip above the system keyboard. Emulator
fills the rest.

## Error States

- Connect failed: `OpenCodeEmptyState` plus Retry.
- Exited: empty state `Shell ended` plus Retry.
- No network path: same connecting/offline language as other live screens;
  mutations gated by path-ready, not by SSE `live`.

## Permission Behavior

Not applicable. Connection Profile Basic Auth is the gate. Session permission
requests stay in Session Chat.

## Design-System Components Needed

- Reuse `OpenCodeTopBar`, `OpenCodeEmptyState`, `OpenCodeSnackbar`.
- Add catalog icon `terminal` through the design-system icon workflow before
  the home action ships. Product meaning: open Terminal from home.
- Emulator canvas and control strip are feature-local. They are a VT host
  and a handful of extra keys, not a Component Collection primitive.

## Expected results (iOS and Android)

- [x] Home loaded with at least one added project shows a Terminal action
  left of Settings.
- [x] That action is hidden when home is not loaded or there are no added
  projects.
- [x] Tap with All selected snacks `Select a project first.` and does not
  open Terminal.
- [x] Tap with a project selected pushes Terminal for that worktree.
- [x] First open creates a server PTY in that worktree and shows a live
  shell. Second open while it is still running reconnects to the same id.
- [x] Typing and the control strip send input; output renders in the
  emulator.
- [x] Resize updates server cols/rows. Background closes the socket and
  does not delete the PTY. Foreground reconnects if still running.
- [x] Process exit shows `Shell ended` and Retry starts a new PTY.
- [x] v1-only and v2-only servers both work. Session Chat is unchanged.
