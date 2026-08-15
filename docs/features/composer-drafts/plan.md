# Composer Drafts

## User Goal

Keep unsent Session Chat prompt text on this phone so leaving a session and
coming back restores that text in Composer.

## In Scope

- Persist unsent composer prompt text locally, keyed per Connection Profile
  identity + worktree + session id (same keying as model selection; no
  passwords in keys).
- Persist structured `@` agent mentions that belong to that text.
- Restore text and mentions when Session Chat opens that session, including
  after Home, process death, and cold start.
- Debounce writes while typing; flush on leave, successful send, and bind
  identity change.
- Clear the stored draft on successful `prompt_async` / command submit when
  Composer already clears text. Delete the row when the field is emptied.
- Wipe all drafts from Clear All Data.
- Delete stored drafts for a session after successful local session delete.
- Do not persist optimistic placeholder session ids. Keep in-memory text
  across promote and save under the real session id.
- Logs: session id / worktree only — never prompt text.

## Out Of Scope

- Server-side or cross-device draft sync.
- Home Session List draft badge / subtitle.
- Pending attachments.
- Question-request or permission answer drafts (in-memory only).
- Model / variant (already persisted) and agent/mode picker.
- Cursor position, suggestion overlay, and picker sheet state.

## Server APIs

None. Device-local only.

## Expected Platform Logs

- Bind / persist / delete may log `session=` and `worktree=`.
- Never log draft body, mention names as payload, or character contents.
- Failures: error type / message only.

## Domain Objects

- `ComposerDraft` — `text`, `agentMentions`, `savedAtEpochMs`.
- `ComposerDraftStore` — `load` / `save` / `delete` / `deleteSessionEntries` /
  `clearAll`.
- Reuses `ModelSelectionConnectionIdentity`.
- `SessionChatComposer` loads on bind and writes on text changes.
- `ConnectionSessionCoordinator.clearConnectionData` clears drafts.
- `DefaultSessionRepository.deleteSession` deletes that session’s drafts.

## Screen Model

No new screen. `SessionChatComposerState.text` and `agentMentions` are restored
from the store on bind and written back on edit. Question-answer fields stay
in-memory and are unchanged.

## Native Navigation

Session Chat already owns Composer. No new destinations.

## Error States

Store failures are swallowed and logged without text. Composer keeps working
in memory. A failed load leaves Composer empty.

## Permission Request Behavior

Not applicable. Permission and question overlays do not persist answer drafts.

## Design-System Components

Existing Session Chat Composer only. No new reusable UI.
