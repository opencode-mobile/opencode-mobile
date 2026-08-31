# Session Chat

## User Goal

Open a session from the home list and continue work inside a dedicated Session
Chat screen with visible messages and a composer ready for new prompts.

## In Scope

- Open Session Chat when a session row is tapped on iOS and Android.
- Show session context, live-update connection state, and context usage at the
  top of Session Chat.
- Render a local/mock message timeline.
- Render a bottom composer with a text field, selector controls, an
  attachment affordance, and a primary Send action.
- Append the typed prompt as a user message locally after Send.

## Out Of Scope

- SSE streaming and reconnect behavior (see Message Repository + future event stream slices).
- Provider auth or model configuration management.

## Delivered after the scaffold slice

- Real server-backed message history and async prompt send are implemented in
  [Message Repository](../message-repository/plan.md) and wired into Session Chat.
- Foreground SSE observation, reconnect refresh, and live session/message state
  shipped in Foreground Event Stream.
- Abort/stop is wired through `SessionRepository` from the composer.
- Permission requests are observed and resolved from the native composer state;
  mobile exposes allow-once and deny only.
- Message sheet **Revert message** reverts the session from that user turn
  (abort if busy, then `revertSession`, restore the prompt in the composer),
  matching `/undo`. Copy stays clipboard-only. While revert is pending, the
  composer shows an attachment-style chip (`Reverting this message` / `REVERT`)
  with X to cancel. Live timeline truncation after revert is still outstanding.

## Still Out Of Scope

- Dedicated session diff viewer interactions.
- Real attachment handling or mobile file upload (specified separately in
  [File Attachments](../file-attachments/plan.md)).

Session rename from Session Chat overflow is a separate Feature Slice:
[Session Rename](../session-rename/plan.md).

## Server APIs Involved

- `GET /session/{id}/message`
- `POST /session/{id}/prompt_async`
- `GET /config/providers`
- `GET /agent` (v2: `GET /api/agent`)

## Domain Objects

- `session` (id and title) passed from home row to Session Chat destination.
- `message` as local UI projection for timeline rendering.
- model, mode, and Variant as composer selections for the next prompt.
- Screen Model concept represented with immutable state plus explicit actions.

## Screen Model structure

Session Chat uses a **regional Screen Model**: `SessionChatTimeline`,
`SessionChatComposer`, and `SessionInfo` own behavior; `SessionChatViewModel`
is a thin facade. Any change to the facade is an architectural decision — see
[Session Chat decisions](decisions.md#keep-sessionchatviewmodel-as-a-thin-facade).

## UI States

- Loaded with messages.
- Empty composer.
- Composer with pending text input.
- Composer selector changes for model, mode, and Variant.
- Session status bar showing session id copy, SSE state (`live`, `connecting`,
  `offline`), and context usage when token and model-limit data are available.
- Session Running chrome as the last timeline row when session status is not idle
  (`Working`, `Retrying`, `Compacting…`), except while the last painted row is
  Thinking.

## Error States

- Not applicable in this scaffold slice.

## Permission Behavior

- The delivered Session Chat observes pending permission requests for the open
  session and its descendants. The composer exposes allow-once and deny; it does
  not expose persistent `remember` decisions.

## Composer Behavior

- A child session does not accept prompts. The same composer shell shows
  display-only model and thinking-effort chips, “Child sessions cannot be
  prompted.”, and “Back to main session”. Permission and question still
  replace that lockout when pending.
- The composer uses a three-row layout inspired by compact mobile chat UIs:
  prompt input, selector chips, then attachment and Send actions.
- Selector chips show current values only, without visible labels. Tapping a chip
  opens the shared **`OpenCodeBottomSheet`** with one row per option for **model**,
  **agent mode** (server-backed primary/`all` agents that are not hidden), and
  **Variant** (`Default` plus server variant keys for the selected model).
- Agent mode is server-backed by `GET /agent` (v2: `GET /api/agent`). Hidden
  system primaries stay out of the picker. `@` mentions stay the non-primary
  path and do not change the selected agent. The selected agent id is sent with
  async prompts and slash commands. Existing sessions preset Model, Agent, and
  Variant from the latest user-sent turn, or the last explicit chip pick for
  that session. New sessions keep the New Session Model Preference and default
  to `build` when present; if the selected agent disappears, fall back to
  `build` or the first catalog entry.
- Model and Variant are server-backed by `/config/providers`; selected model and
  non-default Variant are sent with async prompts.
- The attachment `+` opens native Photos and Files/document entry points through
  the delivered [File Attachments](../file-attachments/plan.md) slice. It uses
  **Add attachment** rather than the legacy **Attach context** label.
- On V2, Send uses the Follow-up send default (Steer or Queue). Long-press
  flips the next send. A waiting follow-up shows a quiet caption under the
  outgoing footer until the server prompts it; then the caption is gone.
- When this session’s status is not idle, Session Running chrome is the last
  timeline row unless the last painted row is Thinking. See
  [Session Running accessory](#session-running-accessory).

## Session Running accessory

Last-timeline-row busy chrome for the open session. Same left-aligned caption
plus pulsing 6pt/dp status dot as thought-row Working/Thinking chrome.
Feature-local: not a Component Collection primitive. It is Session Chat
timeline status only, the same visual as the thought-row streaming
dot, and is not used on other screens.

Show iff this session’s status is not idle. Optimistic busy on send counts as
the same status. Hide while the last painted timeline row is Thinking — that
row already occupies the bottom. Do not hide for settled Thought, tools, or
streaming text. Always the last list entry while visible; do not pin it to
the composer.

`SessionInfo` owns session status. Optimistic busy is write-through to
`SessionStatusLiveStore` so home Running and chat Working share one map.
The timeline owns last-row layout and a stable list identity.
`WorkingTurnIndicator` collapses to status + compacting + awaiting-user +
last-is-thinking.

### Labels (one slot, status only)

- `Working` — busy, including optimistic busy after send, before SSE busy
- `Retrying` — retry
- `Compacting…` — compact in flight

Not `Thinking`. That word stays on the thought row while reasoning is open.

### Expected results (iOS and Android)

- [x] After Send, `Working` appears immediately (optimistic busy) as the last
  timeline row, left-aligned.
- [x] While SSE reports busy, `Working` stays visible as that same last row,
  including while tools run and after a thought row settles to `Thought` /
  `Thought · Nms`.
- [x] While the last painted row is `Thinking`, the Working footer is hidden.
  Thinking and Working never stack.
- [x] Retry shows `Retrying`. Compact in flight shows `Compacting…`.
- [x] The row hides when session status is idle.
- [x] The row hides while the permission or question dock is up. Child
  lockout does not hide it if that session is still busy.
- [x] Top-bar SSE chrome (`live` / `connecting` / `offline`) is unchanged.
- [x] Thought-row chrome is unchanged (`Thinking` with pulsing dot while
  reasoning streams; `Thought` / `Thought · Nms` with no dot when finished).

### Out of scope

- TUI interrupt wording; abort already lives on the composer.
- Elapsed timer.
- Merging with top-bar connection chrome or with Review / Todos / Syncing
  floating pills.
- Full-screen spinner.

## Session Status Bar

- The bar sits directly under `OpenCodeTopBar` and has a fixed 36 dp/pt height.
- The left side shows `Session: <last 9 characters>` plus a tiny copy icon. Only
  the copy icon is tappable, uses a circular pressed/ripple effect, and copies
  the raw full session id.
- The SSE indicator maps foreground event-stream state to lowercase product
  labels: `Connected` -> `live`, `Connecting`/`Reconnecting` -> `connecting`,
  occupying `Idle`/`Disconnected` -> `connecting`, and stopped / no-path /
  no-profile -> `offline`. Send, abort, create, and permission replies use
  path-ready `canMutate`, not SSE `live`.
- The context ring uses the latest assistant input token count divided by the
  selected or message model's context limit. If either value is unavailable, the
  ring renders as unavailable rather than as 0%.
- Context ring color uses `icon-strong-base` below 70%, `warning` from 70%,
  and `error` from 85%.

## Design-System Components Needed

- Reuse existing `OpenCodeListItem` on home for session rows.
- Use existing theme tokens and typography for Session Chat scaffold.
- Use the shared **`OpenCodeSessionStatusBar`** component for session identity,
  foreground SSE state, and context usage.
- Reuse **`OpenCodeBottomSheet`** for composer option pickers (model, mode,
  Variant).
- Use **`OpenCodeOutgoingMessage`** for sent prompts and
  **`OpenCodeAssistantMessage`** for assistant text blocks.
- Session Running chrome is feature-local (pulsing 6pt/dp status dot already
  used on thought-row chrome). Do not add it to Component Collection: it is
  Session Chat last-timeline-row status only and is not used on other screens.
