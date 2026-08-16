# Research: Composer Drafts (OPE-164)

**Date:** 2026-08-15
**Linear:** [OPE-164 Composer Drafts](https://linear.app/opencode-mobile/issue/OPE-164/composer-drafts)
**Question:** How does unsent Session Chat prompt text live today, and what existing local-store pattern should a device-local Composer Draft follow?
**Primary sources:** this repo’s Session Chat composer, model-selection store, Clear All Data, and session delete paths. No server draft API exists.

This note is investigation only. It does not replace `plan.md`.

## Verdict

Composer prompt text is **in-memory only** on `SessionChatComposer`. Leaving Session Chat, process death, and `bind`/`rebind` on a new session identity all drop it. There is no disk store and no `docs/features/composer-drafts/` plan yet.

The ticket’s suggested shape matches the code: a **new deep store** (not Session Cache), keyed like `SessionModelSelectionStore` (connection identity + worktree + session id, no password), DataStore on Android and UserDefaults on iOS. Persist `{ text, agentMentions }`. Restore on bind. Debounce writes and flush on leave. Clear on successful send, blank field, session delete, and Clear All Data.

Hardest implementation seams: `bind`/`rebind` currently **wipe** composer state; optimistic create **promotes** by rebinding to the real id (another wipe); slash New **destroys** the old ViewModel, so a debounce-only write will lose the last keystrokes unless leave flushes.

Question-request answer drafts stay in-memory (`docs/features/question-request/decisions.md`). Attachments stay out of scope.

## Current behavior

### Where the draft lives

| | Android | iOS |
| --- | --- | --- |
| UI model | `SessionChatComposerState.text` + `agentMentions` in `ui/sessionchat/SessionChatUiModels.kt` | same in `UI/SessionChat/SessionChatUiModels.swift` |
| Owner | `SessionChatComposer` `MutableStateFlow` (`region/SessionChatComposer.kt`) | `SessionChatComposer.state` (`Region/SessionChatComposer.swift`) |
| Persistence | none | none |

`canSend` / `hasSendableContent` is `text` (Android: non-blank; iOS: trimmed) or attachments. Mentions are a parallel `AgentMention(id, name, start, end)` list, not just `@name` in the string (`domain/agent/AgentModels.kt`, `Domain/Agent/AgentModels.swift`). Edits go through `ComposerMentionRouting.reconcileAfterEdit`.

### Why Home / kill drops it

**iOS** (`SessionChatView.swift`): `init` does not build the ViewModel. `.task(id:)` creates a new `SessionChatViewModel` every appearance. Back calls `leaveSession()` → `stopObserving()` + `dismiss()`. `@State` dies with the view. `stopObserving()` does not persist text.

**Android** (`SessionChatScreen.kt`, `Navigation.kt`): `hiltViewModel(key = "$sessionId-$worktree")` (plus optimistic instance UUID). Back pops the nav entry and destroys the ViewModel. Same key can reuse a VM only while the entry stays on the stack.

### `bind` / `rebind` wipe

Android `SessionChatComposer.bind` (`SessionChatComposer.kt` 82–109): early-out only if same session + worktree **and** model catalog already loaded. Otherwise `draftRevision = 0` and `_state.value = SessionChatComposerRegionState()` → empty text and mentions.

iOS `rebind` / `reset` (`SessionChatComposer.swift`): same empty reset when the session id changes.

Android `SessionChatViewModel.bind` also replaces `uiState` with `.initial` on identity change, then calls `composer.bind`. Same-session rebind only updates title / Ready / project label.

### When text is cleared today

Clear is **not** on tap Send. `sendPrompt` snapshots `draftRevision`, keeps text visible, then on successful `prompt_async` / command calls `clearAcceptedSubmission`:

- same `draftRevision` → `text = ""`, `agentMentions = []`
- user typed during flight → keep current text
- HTTP failure → no clear (in-memory “restore”)

### Optimistic / placeholder ids

Placeholder is `"__optimistic_create__"` (`OptimisticSessionCreate` on both platforms). Home New and slash New open a placeholder screen. On `createSession` success, `promoteOptimisticSession` calls `composer.bind` / `rebind` with the real id → **empty composer**. Ticket: do not persist placeholder ids; wait for the real id or migrate once known.

Slash New tests (`runClientSlashAction_new_requestsOptimisticReplaceKeepsDraftNeverRunsCommand`) only prove the **old** VM is not mutated. The replacement screen is a new VM with an empty composer.

### Question drafts (must stay in-memory)

`QuestionAnswerDraft` lives on the ViewModel (`questionDrafts` map). `syncQuestionDraftState` clears on request-id change or empty queue. Specified as disk-off in `docs/features/question-request/decisions.md`.

## Persistence pattern to mirror

### `SessionModelSelectionStore` (closest)

Protocol: `loadSession` / `saveSession` / `deleteSession` / `deleteSessionEntries` / new-session preference / `clearAll`.

Keying (`docs/features/model-selection/reliable-selection-plan.md`):

- normalized server URL + effective username (`opencode` if empty) + **exact** server worktree + session id
- password never in the key
- v2 keys: `modelSelection.v2|session|<encodedUrl>|<encodedUser>|<encodedWorktree>|<encodedSession>`
- last-write-wins by `savedAtEpochMs`; cap 500 session entries per connection identity

Backends:

- Android: Preferences DataStore `session_model_selection` (`DataStoreSessionModelSelectionStore`)
- iOS: UserDefaults JSON (`UserDefaultsSessionModelSelectionStore`)

Not Keychain/Keystore — model ids are not secrets. Prompt text is closer to a secret; ticket still asks to follow this backend. Log session id / worktree only, never draft body (`docs/features/diagnostic-logs/plan.md`).

Suggested draft API:

```text
ComposerDraft { text, agentMentions, savedAtEpochMs }
ComposerDraftStore
  load(connectionIdentity, worktree, sessionId)
  save(...)
  delete(...)
  deleteSessionEntries(connectionIdentity, sessionId)
  clearAll()
```

Reuse `ModelSelectionConnectionIdentity`. No new-session preference scope unless product later wants a pre-create draft (mobile has no pre-creation composer).

Do **not** use Session Cache (server session/message snapshots) or Session Ready (session-id maps, no connection identity, 24h TTL).

### Clear All Data (OPE-13)

`ConnectionSessionCoordinator.clearConnectionData()` already calls `sessionModelSelectionStore.clearAll()` (Android `ConnectionSessionCoordinator.kt` 46–54; iOS `ActiveConnectionManager.swift` 263–270). Settings orchestrator then clears Ready, Session Cache, prefs.

Hook: `composerDraftStore.clearAll()` next to model selection so both Settings paths get it.

### Session delete / archive

`DefaultSessionRepository.deleteSession` success already `deleteSessionEntries` on model selection (Android `DefaultSessionRepository.kt` 71–87; iOS `SessionRepository.swift` 132–145). Same hook for drafts.

`archiveSession` is remote-only; model selection is kept. Ticket: delete draft on local delete/archive if cheap; otherwise Clear All Data + overwrite-on-reuse. Archive should probably **keep** the draft (session still exists on the server).

Remove-project does not wipe model selection. Same default for drafts unless a `deleteWorktree` is added.

## Implementation seams

1. **`bind` must load after wipe.** Today bind always starts empty. After reset, load the stored draft for the new identity (skip if placeholder id).
2. **Flush on leave, not only debounce.** iOS `onDisappear` / `leaveSession` and Android nav pop destroy the VM. Last keystrokes after the debounce window need a synchronous/awaited save.
3. **Optimistic create.** Hold text in memory on the placeholder VM; on promote, save under the real id **before** or instead of empty rebind, then restore. Do not write `__optimistic_create__` keys.
4. **Mentions.** Persist `agentMentions` with text. `restoreComposerText` (undo) drops mentions — do not reuse that helper for disk restore.
5. **Successful send.** Call `delete` in the same `clearAcceptedSubmission` path that empties text. Blank field → delete row.
6. **Slash New.** Old session’s draft must be flushed before the VM dies; new placeholder session starts empty (no draft until real id).
7. **Logs.** Counts / session id only. Never prompt text.

## Acceptance mapping

| Ticket acceptance | Code implication |
| --- | --- |
| Type → Home → reopen same session | Persist on edit/leave; load in `bind` |
| Same after force-quit | DataStore / UserDefaults, not VM memory |
| Other session isolated | Key includes session id (+ connection + worktree) |
| Successful send → empty on reopen | `delete` in `clearAcceptedSubmission` |
| Clear All Data removes drafts | `clearAll` in `clearConnectionData` |

## Tests to add (ticket)

Store unit tests on both platforms, modeled on `SessionModelSelectionStoreTests` / `DataStoreSessionModelSelectionStoreTest`:

- save / load / isolation across sessions, worktrees, connection identities
- blank delete
- `clearAll`
- `deleteSessionEntries` after session delete
- last-write-wins / optional eviction cap

Composer / ViewModel tests:

- bind restores text + mentions
- bind of another session does not leak
- successful send deletes; failed send keeps stored + in-memory text
- placeholder bind does not persist; promote writes real id
- question drafts still not persisted

## Open product choices (not blocking a first plan)

- UserDefaults/DataStore vs Keychain/Keystore for prompt text (ticket says follow model selection).
- Eviction cap (model selection uses 500).
- Delete draft on archive / remove-project (model selection does not).
- Whether a draft typed during optimistic create survives promote (ticket says wait for real id or migrate; UX should keep the in-memory text).

## Sources

- [OPE-164](https://linear.app/opencode-mobile/issue/OPE-164/composer-drafts)
- `android/.../ui/sessionchat/region/SessionChatComposer.kt`
- `ios/.../UI/SessionChat/Region/SessionChatComposer.swift`
- `android/.../domain/model/SessionModelSelection.kt`
- `ios/.../Domain/Model/SessionModelSelectionStore.swift`
- `docs/features/model-selection/reliable-selection-plan.md`
- `docs/features/clear-data/`
- `docs/features/question-request/decisions.md`
- `docs/features/diagnostic-logs/plan.md`
