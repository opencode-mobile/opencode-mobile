# Session Chat

## User Goal

Open a session from the home list and continue work inside a dedicated Session
Chat screen with visible messages and a composer ready for new prompts.

## In Scope

- Open Session Chat when a session row is tapped on iOS and Android.
- Show session context (title and id) at the top of Session Chat.
- Render a local/mock message timeline.
- Render a bottom composer with a text field, selector controls, an
  attachment affordance, and a primary Send action.
- Append the typed prompt as a user message locally after Send.

## Out Of Scope

- SSE streaming and reconnect behavior (see Message Repository + future event stream slices).
- Provider auth or model configuration management.

## Delivered via Message Repository slice

- Real server-backed message history and async prompt send are implemented in
  [Message Repository](../message-repository/plan.md) and wired into Session Chat.
- Abort, permission request, and diff interactions.
- Real attachment handling or mobile file upload.

## Server APIs Involved

- `GET /session/{id}/message`
- `POST /session/{id}/prompt_async`
- `GET /config/providers`

## Domain Objects

- `session` (id and title) passed from home row to Session Chat destination.
- `message` as local UI projection for timeline rendering.
- model, mode, and Variant as composer selections for the next prompt.
- Screen Model concept represented with immutable state plus explicit actions.

## UI States

- Loaded with messages.
- Empty composer.
- Composer with pending text input.
- Composer selector changes for model, mode, and Variant.

## Error States

- Not applicable in this scaffold slice.

## Permission Behavior

- Not applicable in this scaffold slice.

## Composer Behavior

- The composer uses a three-row layout inspired by compact mobile chat UIs:
  prompt input, selector chips, then attachment and Send actions.
- Selector chips show current values only, without visible labels. Tapping a chip
  opens the shared **`OpenCodeBottomSheet`** with one row per option for **model**,
  **agent mode** (`Build` / `Plan`), and **Variant** (`Default` plus server
  variant keys for the selected model).
- Model and Variant are server-backed by `/config/providers`; selected model and
  non-default Variant are sent with async prompts.
- The attachment `+` is visually present as an `Attach context` affordance but
  remains a no-op in this scaffold slice.

## Design-System Components Needed

- Reuse existing `OpenCodeListItem` on home for session rows.
- Use existing theme tokens and typography for Session Chat scaffold.
- Reuse **`OpenCodeBottomSheet`** for composer option pickers (model, mode,
  Variant).
