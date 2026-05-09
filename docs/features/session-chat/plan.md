# Session Chat

## User Goal

Open a session from the home list and continue work inside a dedicated Session
Chat screen with visible messages and a composer ready for new prompts.

## In Scope

- Open Session Chat when a session row is tapped on iOS and Android.
- Show session context (title and id) at the top of Session Chat.
- Render a local/mock message timeline.
- Render a composer and a primary Send action.
- Append the typed prompt as a user message locally after Send.

## Out Of Scope

- Real server-backed message history.
- Real prompt delivery, SSE streaming, and reconnect behavior.
- Abort, permission request, and diff interactions.

## Server APIs Involved

- None in this scaffold slice. Session Chat uses local/mock state only.

## Domain Objects

- `session` (id and title) passed from home row to Session Chat destination.
- `message` as local UI projection for timeline rendering.
- Screen Model concept represented with immutable state plus explicit actions.

## UI States

- Loaded with messages.
- Empty composer.
- Composer with pending text input.

## Error States

- Not applicable in this scaffold slice.

## Permission Behavior

- Not applicable in this scaffold slice.

## Design-System Components Needed

- Reuse existing `OpenCodeListItem` on home for session rows.
- Use existing theme tokens and typography for Session Chat scaffold.
