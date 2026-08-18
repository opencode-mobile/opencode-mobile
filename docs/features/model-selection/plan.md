# Model Selection

## User Goal

Choose the model and optional model variant for the next Session Chat prompt
from the connected opencode server's configured providers.

## In Scope

- Fetch `GET /config/providers?directory={worktree}` for the open project.
- Show usable provider/model options in Session Chat with a two-step picker:
  Provider first, then that Provider's models.
- Initialize selection using this priority:
  1. newest valid intent between timestamped per-session cache and latest user
     message history;
  2. assistant-message history as compatibility fallback;
  3. New Session Model Preference when session state and history cannot identify
     the model;
  4. server default, then first usable catalog model.
- Persist user model/variant changes locally per session and restore them on
  later app launches.
- Remember the New Session Model Preference per Connection Profile + Added
  Project and hand it off to sessions created from mobile Home.
- Resolve selection atomically before enabling prompt send so asynchronous
  cache, catalog, and history completion cannot expose a transient fallback.
- Remember user changes while the Session Chat screen remains open.
- Send `model: { providerID, modelID }` and a non-default top-level `variant`
  through `POST /session/{sessionID}/prompt_async`.

## Out Of Scope

- Editing opencode model defaults or provider configuration.
- Managing provider auth.
- Persisting agent/mode choice (mode remains a local Session Chat selector).
- A pre-creation composer or editable desktop-style draft state. Mobile uses a
  New Session Model Preference handoff because Home creates the session first.
- Recent-models list or model visibility toggles (desktop `models.recent`).
- Cross-project model sync beyond the active server + worktree scope.

## Persistent Cache

The delivered per-session cache is extended by the focused
Reliable Model Selection and New-Session Handoff
plan. That follow-up adds deterministic initialization, a timestamped New
Session Model Preference, cross-client history reconciliation, and ordered
writes.

### Desktop Reference

OpenCode desktop stores model/agent/variant in workspace-local persistence
(`model-selection.v1`) keyed by session id. Composer priority is:

1. saved session selection;
2. agent default;
3. config default → recent models → provider default.

When a session has **no saved selection yet**, desktop bootstraps from the
**last user message** via `local.session.restore(msg)`.

### Mobile Target

Mirror the durable parts of desktop behavior:

| Step | Source | Notes |
| --- | --- | --- |
| 1 | Session selection | Per connection identity + worktree + session id; reconciled with newer user history |
| 2 | History bootstrap | User message first; assistant compatibility fallback |
| 3 | New Session Model Preference | Per connection identity + worktree; new-session seed and existing-session fallback |
| 4 | Catalog fallback | Existing server default / first option |

**Cache keys:** normalized server base URL + effective username + exact
server-provided worktree + session id for session memory; the same key without
session id for the New Session Model Preference. Passwords are excluded.

**Cached value:** `providerId`, `modelId`, optional `variant`, and `savedAt`.

**Write cache when:**

- user picks a model or variant in the composer (session and preference scopes);
- user sends a prompt successfully (confirm session scope only);
- mobile Home creates a session (copy resolved preference/catalog selection to
  the new session scope before navigation).

**Cross-client reconciliation:** newer user-message history overrides an older
or legacy session entry and rewrites only that session entry. This intentionally
improves cross-client correctness beyond desktop's strict local-cache guard.

**Unavailable model handling:** keep existing non-blocking notice and fall back
to catalog default; do not delete the stale cache automatically.

### History Bootstrap Alignment

Current mobile bootstrap reads the latest **assistant** message with model
metadata. Desktop reads the latest **user** message when seeding a session with
no saved state.

Implementation should switch bootstrap to **last user message with model
metadata**, with assistant-message hint as fallback when user messages lack
model fields. Record any intentional difference in `parity-gaps.md` if server
data makes full parity impossible.

### Storage

- **Android:** DataStore Preferences JSON blob scoped by connection identity,
  same style as `ConnectionProfileStore`.
- **iOS:** `UserDefaults` JSON blob scoped by connection identity, same style as
  connection profile prefs.
- **Not Keychain:** model choice is not a secret.

Include cache clearing in the existing Developer Tools **Clear All Data** flow.

### Domain Layer

Add a small adapter behind feature code:

- `SessionModelSelection` — cached provider/model/variant tuple plus `savedAt`.
- `SessionModelSelectionStore` — session and preference load/save, session
  deletion, bounded retention, and `clearAll`.
- `NewSessionModelSelectionResolver` — validates preference/catalog fallback and
  prepares the copy-before-navigation handoff.

Generated API types stay outside this store; catalog validation continues to
use `ModelSelectionCatalog`.

### Screen Model Integration

Keep `SessionChatComposer` responsible for catalog application, selection
mutation, and `SessionModelSelectionStore` read/write. Extend initialization
flow:

1. `bind(sessionId, worktree)` resets in-memory composer state, loads cached
   selection, and loads catalog.
2. After catalog is available, resolve selection via
   `tryApplyInitialSelection(timelineState, messageHint)`:
   - reconcile valid timestamped session cache with latest-user history;
   - else apply assistant history as compatibility fallback;
   - else apply the New Session Model Preference;
   - else apply catalog default.
3. Publish selection readiness and the resolved selection atomically; prompt
   send remains disabled until resolution reaches a terminal state.
4. On `selectModel` and `selectVariant`, persist session and preference scopes;
   successful `sendPrompt` confirms the session scope only. Serialize writes per
   scope.

`SessionChatViewModel` only forwards the timeline hint into the composer during
state merge. It must not hold cache state or call the store directly. Both
platforms keep `SessionModelSelectionStore` read/write inside
`SessionChatComposer`.

### Tests

- Store unit tests: save/load scoped by connection identity/worktree/session,
  preference isolation, deletion, retention, migration, and `clearAll`.
- ViewModel tests:
  - newest timestamped intent wins between session cache and user history;
  - legacy cache yields to available user history once;
  - manual picker write persists and survives re-bind;
  - stale cached model shows notice + fallback without deleting cache.
- Reliability tests:
  - resolution is independent of cache/catalog/history completion order;
  - newer history overrides older/legacy cache without changing the preference;
  - newly created sessions receive the same-worktree preference before open;
  - rapid selection writes restore the latest action;
  - preferences never cross server, username, or worktree scope.
- Clear-data test: cache removed after Developer Tools clear.

### Implementation Order

1. Make existing-session resolution observable, atomic, and readiness-gated.
2. Extend domain stores with timestamped preference scope, username-aware
   identity, ordered writes, session deletion, and bounded retention.
3. Save explicit Session Chat choices to session and preference scopes.
4. Seed the per-session entry during mobile Home session creation.
5. Harden stale model/variant behavior and remove stale DTO assumptions.
6. Add completion-order and new-session tests on both platforms.

## Server APIs Involved

- `GET /config/providers`
- `POST /session/{id}/prompt_async`
- `GET /session/{id}/message`

## Domain Objects

- **Model Selection Catalog:** server-backed list of usable model options plus
  the server default.
- **Model Selection Option:** provider id/name, model id/name, and available
  variant keys.
- **Model Selection:** selected model option plus optional selected variant.
- **New Session Model Preference:** explicitly chosen local Model Selection used
  to seed new sessions for one Connection Profile and Added Project.
- **Model Selection Provider:** provider id/name plus the usable model options
  offered by that Provider.

## Screen Model State

- Selected model and optional selected variant.
- Model option rows for the picker.
- Provider groups derived from the model option rows, preserving server order.
- Variant rows: `Default` plus the selected model's variant keys.
- Non-blocking notice when a remembered model is unavailable and a fallback is
  selected.
- Non-blocking notice when a remembered Variant is unavailable and Default is
  used.

## User Actions And Events

- Opening the model picker with no selected model starts at the Provider list.
- Opening the model picker with a selected model starts at that Provider's model
  list.
- Tapping the header back button returns from the model list to the Provider
  list.
- Tapping a model selects it for the composer and dismisses the bottom sheet.

## Design-System Components

Session Chat reuses existing `OpenCodeChoiceBadge` and `OpenCodeBottomSheet`.
The picker layout remains feature-local because it is specific to this composer
flow and does not introduce a new reusable primitive.
