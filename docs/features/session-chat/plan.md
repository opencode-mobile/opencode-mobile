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
  shipped in [Foreground Event Stream](../foreground-event-stream/progress.md).
- Abort/stop is wired through `SessionRepository` from the composer.
- Permission requests are observed and resolved from the native composer state;
  mobile exposes allow-once and deny only.

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

## Error States

- Not applicable in this scaffold slice.

## Permission Behavior

- The delivered Session Chat observes pending permission requests for the open
  session and its descendants. The composer exposes allow-once and deny; it does
  not expose persistent `remember` decisions.

## Composer Behavior

- The composer uses a three-row layout inspired by compact mobile chat UIs:
  prompt input, selector chips, then attachment and Send actions.
- Selector chips show current values only, without visible labels. Tapping a chip
  opens the shared **`OpenCodeBottomSheet`** with one row per option for **model**,
  **agent mode** (`Build` / `Plan`), and **Variant** (`Default` plus server
  variant keys for the selected model).
- Model and Variant are server-backed by `/config/providers`; selected model and
  non-default Variant are sent with async prompts.
- The attachment `+` opens native Photos and Files/document entry points through
  the delivered [File Attachments](../file-attachments/plan.md) slice. It uses
  **Add attachment** rather than the legacy **Attach context** label.

## Session Status Bar

- The bar sits directly under `OpenCodeTopBar` and has a fixed 36 dp/pt height.
- The left side shows `Session: <last 9 characters>` plus a tiny copy icon. Only
  the copy icon is tappable, uses a circular pressed/ripple effect, and copies
  the raw full session id.
- The SSE indicator maps foreground event-stream state to lowercase product
  labels: `Connected` -> `live`, `Connecting`/`Reconnecting` -> `connecting`,
  and `Idle`/`Disconnected` -> `offline`.
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
