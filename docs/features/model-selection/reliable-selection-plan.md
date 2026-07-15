# Reliable Model Selection and New-Session Handoff

## Status

Implemented for OPE-45 on 2026-07-15 after a `grill-with-docs` review of the
current `opencode` web implementation at `cb8be9ba1` and the mobile baseline at
`dabc7a1`.

## Goal

Make the model shown when Session Chat becomes interactive the model the user is
most likely to expect:

- reopening a session restores the newest explicit intent between local session
  state and user-message history;
- a session first opened on this device restores the latest user-message model;
- a newly created session starts with the New Session Model Preference for that
  Connection Profile and Added Project; and
- catalog fallback is never briefly exposed as the effective selection while a
  more authoritative source is still loading.

## OpenCode Web Reference

The current web app separates three kinds of state.

| State | Web owner and scope | Behavior to mirror |
| --- | --- | --- |
| Session selection | `context/local.tsx`, `model-selection.v1`, keyed by server workspace and session id | A saved session model/variant wins. If missing, the last user message seeds it once. |
| New-session draft | `context/prompt-state.ts`, workspace prompt scope without a session id | The model selected before creation is retained and promoted into the new session. |
| Model recency and variant memory | `context/models.tsx`, global `model.v1` | Explicit model picks update recent models; variants are remembered per model. New-session fallback uses configured model, then recent model, then provider default. |

The new-session submission path calls `local.session.promote(...)` with the
effective model and variant before navigation. The target Session screen can
therefore read the new session selection immediately instead of waiting for
history that does not exist yet.

Mobile does not currently have a pre-creation composer, so exact draft-state
parity is not possible without a separate product change. The equivalent mobile
contract is a New Session Model Preference plus an explicit handoff into the
newly created session.

## Current Mobile Findings

### Existing behavior

- `SessionModelSelectionStore` persists `provider/model/variant` per normalized
  server URL, worktree, and session id.
- `SessionChatComposer` resolves cache, then message history, then catalog
  default on both platforms.
- Explicit model and variant changes and successful sends update the
  per-session entry.
- The last user message is the preferred history hint, with assistant metadata
  as a compatibility fallback.

### Reliability gaps

1. **Android cache completion is not observable.** `loadCachedSelection()`
   mutates `cachedSelection` and `cacheResolved` without emitting composer
   state. If catalog and timeline emissions happen before the DataStore read
   completes, `tryApplyInitialSelection()` may never run again and the catalog
   fallback remains selected.
2. **Fallback is published before resolution.** `applyCatalog()` immediately
   selects the server default or first model while cache/history resolution is
   still pending. The composer can briefly display and enable sending with the
   wrong model.
3. **New sessions have no selection handoff.** Home creates an empty server
   session and navigates to it. With no per-session cache and no history, the
   composer always starts from catalog fallback.
4. **There is no worktree-level memory.** A model explicitly chosen in one
   session cannot become the starting choice for another new session in the
   same worktree.
5. **Write ordering is implicit.** Rapid model/variant changes launch
   independent persistence tasks. The contract does not guarantee that the
   final stored value is the latest user action.
6. **The mobile DTO has stale session-level model fields.** Current OpenCode
   `Session` does not expose model selection. Message metadata remains the
   server-backed recovery source; the plan must not depend on `Session.model`.

## Shared Product Contract

### Selection scopes

Use two persisted scopes behind `SessionModelSelectionStore`:

| Scope | Key | Purpose |
| --- | --- | --- |
| Session | normalized server URL + effective username + exact server-provided worktree + session id | Restore an existing session on this device and reconcile it with newer user-message intent. |
| New Session Model Preference | normalized server URL + effective username + exact server-provided worktree | Seed a session created from mobile when no session-specific value exists. |

Both scopes store provider, model, optional variant, and `savedAt`. Passwords
never participate in the key. Model selection is not a secret and remains
outside Keychain/Keystore storage. Do not normalize the worktree as a local
filesystem path because the server may run on another operating system.

### Existing-session priority

Resolve exactly once per bind, after cache, catalog, and the required history
state have reached a terminal result. First reconcile valid session cache with
the latest user message:

- if only one exists, use it;
- if both have timestamps, use the newer intent and let session cache win a tie;
- if a legacy cache has no `savedAt`, prefer user history once and rewrite the
  session entry in the versioned format;
- if no user message exists, a valid legacy cache remains usable.

Then continue through the remaining sources:

1. the reconciled session-cache or latest-user result;
2. latest assistant message with model metadata, only as compatibility fallback;
3. valid New Session Model Preference;
4. server-configured default from `/config/providers`;
5. first usable catalog model.

When newer user history wins, update only the session entry. Do not change the
New Session Model Preference. This time-aware rule intentionally improves
cross-client correctness beyond web's strict local-cache precedence.

### New-session priority

For a session created from mobile Home:

1. valid New Session Model Preference;
2. server-configured default;
3. first usable catalog model.

After `POST /session` succeeds and before navigation, copy the resolved New
Session Model Preference or catalog fallback into the new session-scoped entry.
If the catalog cannot be loaded during creation, navigation still proceeds and
Session Chat performs normal resolution; session creation must not be blocked
by selection memory.

### Writes

- An explicit model pick saves both the session selection and New Session Model
  Preference.
- An explicit variant pick saves both scopes as one model/variant tuple.
- An accepted prompt confirms the session scope only. It does not turn a
  history-restored or fallback model into a New Session Model Preference.
- History bootstrap and catalog fallback seed the session scope only after
  initial resolution; they do not overwrite the New Session Model Preference
  merely because a session was opened.
- Persistence operations are serialized per scope so an older asynchronous
  write cannot replace a newer choice.
- Successful session deletion removes its session entry but retains the New
  Session Model Preference.
- Keep at most 500 session entries per Connection Profile, evicting the oldest
  `savedAt` entries. Clear All Data removes both scopes and migration metadata.

### Readiness and unavailable values

- Expose model-selection readiness independently from catalog availability.
- Do not enable prompt send until initial selection is resolved.
- Show a loading model control while resolving; do not
  present catalog fallback as final while a higher-priority source is pending.
- Validate every restored value against the current connected catalog.
- If a session-scoped selection is unavailable, continue down the priority
  chain and show the existing non-blocking notice.
- Preserve the stale entry for recovery if the provider returns later, unless
  the user explicitly chooses another model.
- If a remembered variant is unavailable for an otherwise valid model, keep the
  model, use Default, and show a non-blocking variant notice.
- Cache read/write failures never block opening or creating a session. Log the
  failure, then resolve through history and catalog without exposing a storage
  error in the composer.
- Catalog failure leaves Send disabled, shows the actionable catalog error, and
  allows retry because no effective model can be validated.

## Screen Model and Domain Changes

### Domain store

Extend the store without exposing platform persistence details:

```text
loadSession(connectionIdentity, worktree, sessionId)
saveSession(connectionIdentity, worktree, sessionId, selection, savedAt)
deleteSession(connectionIdentity, worktree, sessionId)
loadNewSessionPreference(connectionIdentity, worktree)
saveNewSessionPreference(connectionIdentity, worktree, selection, savedAt)
clearAll()
```

`connectionIdentity` contains normalized server URL and effective username, but
never a password. Use a versioned encoded key or record shape so existing
session entries migrate without loss. Legacy entries remain readable until
history reconciliation rewrites them with `savedAt`. Keep URL normalization and
the 500-entry eviction rule identical across both platforms.

### Session Chat Composer

Keep orchestration inside `SessionChatComposer`:

- replace `cacheResolved` plus incidental callbacks with an explicit
  initialization state (`loading`, `resolved`, or `failed-with-fallback`);
- make cache completion emit/notify so resolution cannot depend on another
  region changing later;
- resolve from an immutable snapshot of session cache, timestamped timeline
  hint, New Session Model Preference, and catalog;
- publish selected model and readiness in one state update; and
- serialize selection persistence through the composer or store.

`SessionChatViewModel` remains a thin coordinator. It may forward the timeline
hint but must not own selection cache state.

### New-session creation

Home Screen Models remain coordinators and call a shared
`NewSessionModelSelectionResolver` domain service:

1. load the New Session Model Preference and catalog concurrently;
2. validate and resolve the new-session selection;
3. create the session;
4. save the resolved selection under the returned session id;
5. navigate to Session Chat.

Do not move picker or Session Chat state into Home. Forked sessions derive their
selection from copied user-message history; only a truly empty session falls
through to the New Session Model Preference.

## Implementation Phases

### Phase 1: deterministic existing-session initialization

- Add explicit selection readiness to composer state on both platforms.
- Make cache completion observable on Android and iOS.
- Prevent send until selection resolution completes.
- Resolve and publish the effective selection atomically.
- Add race-focused tests with cache, catalog, and history completing in every
  relevant order.

### Phase 2: New Session Model Preference and handoff

- Extend stores with timestamped New Session Model Preference scope,
  username-aware connection identity, migration-safe keys, deletion, and
  bounded retention.
- Save only explicit model/variant picks to the New Session Model Preference.
- Resolve a new-session selection and seed its session entry before navigation.
- Preserve successful session creation when catalog/cache lookup fails.

### Phase 3: recovery and conformance hardening

- Serialize writes per scope.
- Cover stale model and stale variant independently.
- Remove reliance on stale `Session.model` DTO fields after confirming no other
  mobile feature consumes them.
- Add logs for source chosen (`session-cache`, `user-history`,
  `assistant-history`, `new-session-preference`, `server-default`,
  `first-usable`) with opaque identifiers only, without logging credentials or
  prompt content.

## Acceptance Scenarios

1. Reopening a session with a valid cache newer than user history shows the
   cache value, regardless of cache/catalog/history completion order.
2. Newer user history overrides an older or legacy session cache and rewrites
   only the session entry.
3. Assistant history is used only when no user message exposes model metadata.
4. Creating a session after choosing model B in another session in the same
   worktree opens with model B and its valid variant.
5. A different worktree does not inherit model B.
6. A stale session model falls through to history, then preference/default,
   with a non-blocking notice.
7. A stale variant keeps the model, uses Default, and shows a variant notice.
8. The send action is disabled until initialization resolves and never submits
   a transient catalog fallback.
9. Rapid B to C model changes restore C after process restart.
10. Successful session deletion removes its session entry; clearing all data
    removes both scopes and migration metadata.
11. Cache read/write failure does not prevent opening or creating a session;
    catalog fallback remains available after resolution reaches a failure state.
12. iOS and Android choose the same source and effective selection for the same
    catalog, history, and stored inputs.
13. The same server URL with a different username or worktree does not share
    preferences.
14. Cache failure degrades to history/catalog without blocking navigation;
    catalog failure keeps Send disabled and can be retried.
15. The 501st session entry evicts the oldest timestamped entry for that
    Connection Profile.

## Verification

- Android unit tests for composer resolution permutations, timestamp
  reconciliation, ordered writes, preference scoping, Home handoff, deletion,
  retention, stale values, and clear-data behavior.
- iOS Swift Testing equivalents for the same shared scenarios.
- Focused Android JVM test task and iOS test target for touched suites.
- Manual cold-launch checks on both platforms: existing cached session,
  history-only session, and newly created session.
- Compare observed choices with current OpenCode web for equivalent saved,
  history, configured-default, and recent-selection inputs.

## Risks and Boundaries

- Mobile has no new-session composer, so it can mirror web handoff semantics but
  not the exact pre-send draft UX.
- The New Session Model Preference is local device state; selections made on
  another web/mobile client affect existing sessions through newer user-message
  history but do not change the preference.
- Server-configured agent model precedence is not included because mobile's
  Build/Plan selector is not yet backed by the web agent model contract.
- Full recent-model lists, visibility controls, and global cross-worktree
  recency remain out of scope.
- No API change is required; `/config/providers`, `/session`, and session
  message history are sufficient.
