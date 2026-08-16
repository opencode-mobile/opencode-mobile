# Composer Drafts — Decisions

## Follow model-selection persistence, not Session Cache

Drafts are device-local composer state, not server session/message snapshots.
Use DataStore (Android) and UserDefaults (iOS) with the same connection
identity + worktree + session keying as `SessionModelSelectionStore`. Do not
put prompt text in Session Cache or Session Ready.

Prompt text is more sensitive than a model id. Still use the model-selection
backends because the ticket asked for that posture and the text never leaves
the device. Logs must never include draft body.

## Persist mentions with text

`AgentMention` ranges are not recoverable from `@name` alone after later
edits. Store them next to `text`. Do not reuse `restoreComposerText` (undo),
which drops mentions.

## Do not persist placeholder session ids

Never write `__optimistic_create__`. Hold in-memory text during create; on
promote, save under the real session id.

## Delete on send, blank, session delete, and Clear All Data

Successful submit that clears Composer also deletes the stored row. Empty
text deletes the row. Session delete calls `deleteSessionEntries`. Clear All
Data calls `clearAll` next to model selection.

Archive and remove-project do not delete drafts (same as model selection).
Overwrite-on-reuse and Clear All Data cover leftovers.

## Cap and last-write-wins

Same as model selection: last-write-wins by `savedAtEpochMs`, 500 session
entries per connection identity.
