# Terminal

## Now

Home Terminal action opens a Terminals catalog of running shells across added
projects. Tap a row to attach that PTY. Trailing close deletes it. Empty catalog
does not create a shell. Session Chat `[terminal]` pushes a project shell and
Back returns to the same chat. Dual remotes, bundled xterm.js WebView with
native WebSocket I/O, Ctrl-C / Esc / Tab / arrows strip, reconnecting after a
dropped socket.

## Remaining

Connect-token HTTP when the server requires `x-opencode-ticket`. Hardware
keyboard shortcuts. Confirm CI/Xcode packaging of `shared/terminal-web`.

## User Goal

From home, open a live shell on the connected opencode server for an added
project, type commands, and see output, without leaving the app for SSH.

## In Scope

- A Terminals catalog page listing running PTYs across all added projects for
  the active Connection Profile. Home session list is unchanged.
- Home top-bar Terminal action (icon left of Settings) when home is loaded and
  there is at least one added project. Tap opens the catalog. The icon ignores
  All vs a project chip. Hide the action when home is not loaded or there are
  no added projects.
- Catalog rows: letter badge, title (`zsh` or server title), subtitle project
  label, trailing close. Tap pushes Terminal for that worktree and that PTY id.
  Close is `DELETE /pty/{id}` then drop the row. Empty: `No running shells`.
  Do not create a PTY from this page. Back returns to the same home session list.
- One Terminal screen on iOS and Android. Opening a catalog row connects that
  id. Opening without an id still `ensureRunning` for that worktree.
- Foreground WebSocket to `/pty/{id}/connect` (v2: `/api/pty/{id}/connect`).
  Close the socket on background. Do not delete the PTY when leaving the
  screen or backgrounding. Reopen reconnects if it is still running.
- Dual remotes. Same Screen Model on v1 and v2.
- Bundled xterm.js in a local WebView. Map theme tokens into the emulator
  inside the host. Do not load remote JS.
- A feature-local control strip: Ctrl-C, Esc, Tab, arrows. Mobile keyboards do
  not send those reliably.
- Product errors: connect failed, process exited, project missing.

## Out Of Scope

- `POST /session/{id}/shell` and the Session Chat bash sheet. Those are
  agent tool output, not a terminal.
- `/tui/*`. That drives a desktop TUI that is already running.
- Session Chat embedding, split view, or a tab inside chat.
- Creating a PTY from the catalog. Start from Session Chat `[terminal]`.
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
- `DELETE /pty/{id}`
- `GET /pty/{id}/connect` upgraded to WebSocket
- connect ticket when the server requires `x-opencode-ticket` (same as
  official web)

v2 equivalents: `/api/pty` and `/api/pty/{id}/connect` with
`location[directory]`. Delete is `DELETE /api/pty/{id}` with
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
- Selected added project worktree, passed from the catalog (or home, when
  opening without an id). Terminal does not pick a project itself.
- `TerminalRepository`: list, create, get, update size, connect, remove,
  list running across worktrees.

## Screen Model structure

`TerminalsCatalogViewModel` and `TerminalViewModel` are separate regions.
Immutable UI state plus explicit actions. They do not live under
`SessionChatViewModel`.

Catalog actions: `appear`, `close(id, directory)`. Terminal actions:
`appear`, `disappear`, `retry`, `foreground`, `background`,
`resize(cols, rows)`. Byte I/O stays in the WebView host, not in UI state.

## UI States

Catalog:

- Loaded rows
- Empty (`No running shells`)

Terminal:

- Connecting
- Live
- Reconnecting (socket dropped, process still running)
- Exited (process ended; primary Retry creates a new PTY)
- Connect failed

Catalog chrome: `OpenCodeTopBar` title `Terminals`, leading back. No dock.
No New session.

Terminal chrome: `OpenCodeTopBar` title `Terminal`, subtitle the project
label with letter badge, leading back. Control strip above the system
keyboard. Emulator fills the rest.

## Error States

- Connect failed: `OpenCodeEmptyState` plus Retry.
- Exited: empty state `Shell ended` plus Retry.
- No network path: same connecting/offline language as other live screens;
  mutations gated by path-ready, not by SSE `live`.

## Permission Behavior

Not applicable. Connection Profile Basic Auth is the gate. Session permission
requests stay in Session Chat.

## Design-System Components Needed

- Reuse `OpenCodeTopBar`, `OpenCodeEmptyState`, `OpenCodeSnackbar`,
  `OpenCodeListItem`.
- Catalog icon `terminal` through the design-system icon workflow. Product
  meaning: open Terminals from home.
- Emulator canvas and control strip are feature-local. They are a VT host
  and a handful of extra keys, not a Component Collection primitive.

## Expected results (iOS and Android)

- [x] Home loaded with at least one added project shows a Terminal action
  left of Settings.
- [x] That action is hidden when home is not loaded or there are no added
  projects.
- [x] Tap opens the Terminals catalog. All vs a project chip does not
  change that. Session list is unchanged.
- [x] Catalog lists running PTYs across all added projects. Tap a row
  connects that PTY id. Close deletes it and drops the row.
- [x] Empty catalog shows `No running shells` and does not create a PTY.
- [x] First open without an id creates a server PTY in that worktree and
  shows a live shell. Second open while it is still running reconnects to
  the same id.
- [x] Typing and the control strip send input; output renders in the
  emulator. Strip is Ctrl-C, Esc, Tab, arrows.
- [x] Resize updates server cols/rows. Background closes the socket and
  does not delete the PTY. Foreground reconnects if still running.
- [x] Process exit shows `Shell ended` and Retry starts a new PTY.
- [x] v1-only and v2-only servers both work. Session Chat is unchanged except
  `[terminal]` pushing a project shell.
- [x] Session Chat `[terminal]` starts or reuses the worktree PTY and Back
  returns to the same chat.
