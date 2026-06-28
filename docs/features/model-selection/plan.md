# Model Selection

## User Goal

Choose the model and optional model variant for the next Session Chat prompt
from the connected opencode server's configured providers.

## In Scope

- Fetch `GET /config/providers?directory={worktree}` for the open project.
- Show usable provider/model options in Session Chat with a two-step picker:
  Provider first, then that Provider's models.
- Initialize selection using this priority:
  1. persisted per-session cache (if model still exists in catalog);
  2. history bootstrap when no cache exists yet;
  3. server default, then first usable catalog model.
- Persist user model/variant changes locally per session and restore them on
  later app launches.
- Remember user changes while the Session Chat screen remains open.
- Send `model: { providerID, modelID }` and a non-default top-level `variant`
  through `POST /session/{sessionID}/prompt_async`.

## Out Of Scope

- Editing opencode model defaults or provider configuration.
- Managing provider auth.
- Persisting agent/mode choice (mode remains a local Session Chat selector).
- Global workspace model default without an open session (desktop draft state).
- Recent-models list or model visibility toggles (desktop `models.recent`).
- Cross-project model sync beyond the active server + worktree scope.

## Persistent Cache

### Desktop Reference

OpenCode desktop stores model/agent/variant in workspace-local persistence
(`model-selection.v1`) keyed by session id. Composer priority is:

1. saved session selection;
2. agent default;
3. config default → recent models → provider default.

When a session has **no saved selection yet**, desktop bootstraps from the
**last user message** via `local.session.restore(msg)`.

### Mobile Target

Mirror the durable parts of desktop behavior without expanding v1 scope:

| Step | Source | Notes |
| --- | --- | --- |
| 1 | `SessionModelSelectionStore` | Per `server + worktree + sessionId` |
| 2 | History bootstrap | Only when step 1 is missing |
| 3 | Catalog fallback | Existing server default / first option |

**Cache key:** normalized server base URL + worktree directory + session id.

**Cached value:** `providerId`, `modelId`, optional `variant`.

**Write cache when:**

- user picks a model or variant in the composer;
- user sends a prompt successfully (confirm latest effective selection).

**Do not overwrite cache from history** once a session entry exists (desktop
`restore` guard).

**Unavailable model handling:** keep existing non-blocking notice and fall back
to catalog default; do not delete the stale cache automatically in v1.

### History Bootstrap Alignment

Current mobile bootstrap reads the latest **assistant** message with model
metadata. Desktop reads the latest **user** message when seeding a session with
no saved state.

Implementation should switch bootstrap to **last user message with model
metadata**, with assistant-message hint as fallback when user messages lack
model fields. Record any intentional difference in `parity-gaps.md` if server
data makes full parity impossible.

### Storage

- **Android:** DataStore Preferences JSON blob scoped by server URL, same style
  as `ConnectionProfileStore`.
- **iOS:** `UserDefaults` JSON blob scoped by server URL, same style as
  connection profile prefs.
- **Not Keychain:** model choice is not a secret.

Include cache clearing in the existing Developer Tools **Clear All Data** flow.

### Domain Layer

Add a small adapter behind feature code:

- `SessionModelSelection` — cached provider/model/variant tuple.
- `SessionModelSelectionStore` — `load`, `save`, `clearAll`.
- Scope inputs: `serverBaseUrl`, `worktree`, `sessionId`.

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
   - read cached selection and validate against catalog;
   - else apply history bootstrap once (`LatestModelHint` from timeline);
   - else apply catalog default.
3. On `selectModel`, `selectVariant`, and successful `sendPrompt`, persist
   effective selection through the store.

`SessionChatViewModel` only forwards the timeline hint into the composer during
state merge. It must not hold cache state or call the store directly. iOS still
needs parity: move cache orchestration from `SessionChatViewModel` into
`SessionChatComposer` to match Android.

### Tests

- Store unit tests: save/load scoped by server/worktree/session, `clearAll`.
- ViewModel tests:
  - cached selection wins over history and catalog default;
  - history seeds only when cache missing;
  - manual picker write persists and survives re-bind;
  - stale cached model shows notice + fallback without deleting cache.
- Clear-data test: cache removed after Developer Tools clear.

### Implementation Order

1. Update feature docs (`decisions.md`, `progress.md`).
2. Add domain store + platform persistence on both apps.
3. Wire `SessionChatComposer` read/write paths.
4. Adjust history bootstrap source (user message first).
5. Hook clear-data orchestration.
6. Add tests on both platforms.

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
- **Model Selection Provider:** provider id/name plus the usable model options
  offered by that Provider.

## Screen Model State

- Selected model and optional selected variant.
- Model option rows for the picker.
- Provider groups derived from the model option rows, preserving server order.
- Variant rows: `Default` plus the selected model's variant keys.
- Non-blocking notice when a remembered model is unavailable and a fallback is
  selected.

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
