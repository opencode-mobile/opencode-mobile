# Session Chat

## User Goal

Open a session from the home list and continue work inside a dedicated Session
Chat screen with visible messages and a composer ready for new prompts.

## In Scope

- Open Session Chat when a session row is tapped on iOS and Android.
- Show session context (title and id) at the top of Session Chat.
- Render a local/mock message timeline.
- Render a bottom composer with a text field, local selector controls, an
  attachment affordance, and a primary Send action.
- Append the typed prompt as a user message locally after Send.

## Out Of Scope

- SSE streaming and reconnect behavior (see Message Repository + future event stream slices).
- Server-backed composer option mapping (see [Message Repository](../message-repository/plan.md)).

## Delivered via Message Repository slice

- Real server-backed message history and async prompt send are implemented in
  [Message Repository](../message-repository/plan.md) and wired into Session Chat.
- Abort, permission request, and diff interactions.
- Real attachment handling or mobile file upload.

## Server APIs Involved

- None in this scaffold slice. Session Chat uses local/mock state only.

## Domain Objects

- `session` (id and title) passed from home row to Session Chat destination.
- `message` as local UI projection for timeline rendering.
- model, mode, and Thinking Effort as local composer selections for the next
  prompt.
- Screen Model concept represented with immutable state plus explicit actions.

## UI States

- Loaded with messages.
- Empty composer.
- Composer with pending text input.
- Local composer selector changes for model, mode, and Thinking Effort.

## Error States

- Not applicable in this scaffold slice.

## Permission Behavior

- Not applicable in this scaffold slice.

## Composer Behavior

- The composer uses a three-row layout inspired by compact mobile chat UIs:
  prompt input, selector chips, then attachment and Send actions.
- Selector chips show current values only, without visible labels. Tapping a chip
  opens the shared **`OpenCodeBottomSheet`** with one row per option for **model**,
  **agent mode** (`Build` / `Plan`), and **Thinking Effort** (`Default`, `Low`,
  `Medium`, `High`). Mock models include placeholder entries alongside
  `DeepSeek V4 Flash` until server-backed lists exist.
- Selector changes are local/mock state only until server-backed prompt delivery
  defines real option sources and request mapping.
- The attachment `+` is visually present as an `Attach context` affordance but
  remains a no-op in this scaffold slice.

## Design-System Components Needed

- Reuse existing `OpenCodeListItem` on home for session rows.
- Use existing theme tokens and typography for Session Chat scaffold.
- Reuse **`OpenCodeBottomSheet`** for composer option pickers (model, mode,
  Thinking Effort).
