# Session Ready Decisions

Ticket: [OPE-105](https://linear.app/opencode-mobile/issue/OPE-105/session-ready)

Triage lock (2026-08-04): all product and architecture choices below are
**locked for AFK implementation**. Prefer implementability and “desktop stays
quiet” over literal early ticket wording when they conflict; Linear description
must match these locks.

## Watch triggers: send + focused busy, not home-only busy

- **Decision:** Start a watch when (a) this mobile client accepts a prompt send
  for the session, or (b) `SessionStatusLiveStore` shows `busy` / `retry` for
  the session while Session Chat is **focused** on that session. Do **not**
  start a watch from busy/retry observed only on the home Session List.
- **Why:** Literal “observe busy while foreground-connected” would mark
  desktop/TUI turns as watched whenever home is open, then paint Ready on idle —
  contradicting “desktop stays quiet.” Focusing Session Chat is a clear
  this-device-cares signal without requiring another send.
- **Evidence:** Home already observes `SessionStatusLiveStore` for Running
  pills (`MainScreenViewModel` / `ProjectListHomeViewModel`); that path must
  remain display-only for Ready.
- **Rejected:** Watch on any foreground status observation; watch-on-send only
  (misses “opened chat mid-turn, left while still Working”).

## Ready and watched persist across restart with 24h TTL

- **Decision:** Persist Ready **and** watched entries (session id, directory,
  timestamp) on device. Restore on launch. Auto-expire each entry **24 hours**
  after it was set. On restore, if a watched session is already idle (or absent
  from the busy status map), set Ready (catch-up for process death mid-turn).
  Opening Session Chat, Clear Data, and Connection Profile apply still clear
  immediately.
- **Why:** Users kill the app while waiting; Ready-only persistence misses that
  case because idle never ran on the dead process. Android log evidence:
  `watch started` → `focus cleared` → process killed → no `idle handled` / no
  `session_ready` prefs; iOS (alive) got `outcome=ready`.
- **Rejected:** Persist Ready only; rebuild from `session.time.updated`.
- **Supersedes:** process-local Ready/watched; Ready-only persistence.

## Persist with SharedPreferences / UserDefaults

- **Decision:** Store the Ready/watched snapshot in Android SharedPreferences and
  iOS UserDefaults (JSON blob), not Room / SwiftData.
- **Why:** Small ephemeral cue set with a TTL; sync load on store init matches
  the LiveStore singleton shape. Peers already use preferences-style stores for
  similar non-relational app state.
- **Rejected:** Room / SwiftData for this blob (overkill for a TTL cue map).

## Pill tone is `success`

- **Decision:** Session List maps `SessionListStatus.ready` → pill text
  **Ready**, tone `OpenCodeListItemStatusTone.Success` (iOS `.success`).
- **Why:** Distinguishes finished-available from Idle (`info`) and Running
  (`interactive`). Success already exists on both platforms’ list-item tones.
- **Evidence:** Android `SessionListStatus.toListItemStatus` maps Idle→Info,
  Running→Interactive; iOS `listItemStatus` matches. Success is available on
  `OpenCodeListItemStatusTone`.
- **Rejected:** `info` (collides visually with Idle); `interactive` (reserved
  for live work).

## Needs-approval home priority is reserved, not shipped here

- **Decision:** Document priority
  `needs approval > Running > Ready > idle`, but **do not** add a home Needs
  approval pill in OPE-105. Home ViewModels today do not observe
  `PermissionLiveStore`. Implement the composite mapper with an optional
  `hasPendingPermission` input defaulting to false so a later permission
  attention slice can plug in without remapping Ready.
- **Why:** Permission approval UX lives in Session Chat
  (`docs/features/permission-approval/`). Home attention was noted as future
  mobile work in the permission web-spec, not as existing Session List status.
- **Evidence:** `PermissionLiveStore` is used from Session Chat ViewModels and
  Clear Data orchestration only — no usage in `MainScreenViewModel` or
  `ProjectListHomeViewModel`; `SessionListStatus` is only `idle` | `running`.

## Extend `SessionListStatus` with Ready

- **Decision:** Add `ready` to `SessionListStatus` on both platforms; update
  `docs/design-system/components/session-list.md`, Component Galleries, and
  `toListItemStatus` mappings. Do this through the design-system component
  workflow before home screens depend on the case.
- **Why:** Ready is a first-class Session List status, not a caption hack or
  feature-local override.

## Design-system case is a home-mapping gate, not a domain gate

- **Decision:** `SessionListStatus.ready` (docs + native enum + gallery) must
  land **before or with** the first home mapping that consumes Ready. Domain
  `SessionReadyStore` may land in parallel or first; it does not depend on the
  pill case.
- **Why:** Design-system workflow requires the shared component contract before
  dependent UI. Blocking all domain work on the enum would slow AFK without
  reducing risk.
- **Preferred AFK order:** design-system `ready` → `SessionReadyStore` → Session
  Chat hooks → home observe + composite map → Clear Data / profile
  `clearCache` → unit tests → manual smoke.

## Deep `SessionReadyStore` owns the state machine

- **Decision:** One `SessionReadyStore` module (LiveStore family) owns watched,
  Ready, focus, and status-diff behavior. Home observes Ready ids; Session Chat
  calls `notifyPromptSent` and `setFocusedSession`. Watched is one-shot per
  turn (consumed on idle handling).
- **Why:** Depth and locality — callers should not know storage shape or the
  watch/idle/focus interplay. Aligns with `SessionStatusLiveStore` /
  `SessionTodoLiveStore` seams rather than a third event architecture.
- **Rejected:** Flags in ViewModels only; folding Ready into
  `SessionStatusLiveStore` (would widen an unrelated status interface).

## Session Chat open = explicit focus registration

- **Decision:** Detect “open” via `setFocusedSession(sessionId)` from Session
  Chat bind / `startObserving`, and `setFocusedSession(null)` on teardown /
  `stopObserving` / `onCleared`. Clear Ready when focus lands on that session
  id. Also clear any watched entry for that session, and treat focus as an
  idle baseline in `previousTypes` when the session is not already busy/retry.
- **Why:** Reliable across Android `bind` reuse and iOS per-session ViewModel
  lifetime without parsing navigation graphs. Opening an idle Ready session can
  receive `session.status` (speculative watch) and then
  `restoredAlreadyIdle` after leave when idle rows are absent from the status
  map — that re-stamped Ready despite the user opening the session to clear it.
- **Evidence:** Android `SessionChatViewModel.bind`; iOS
  `SessionChatViewModel.startObserving` / `stopObserving` (view lifecycle).
  Bug report logs: open Ready → leave → Ready still shown after refresh.

## Live idle catch-up is opt-in, excluded for speculative watches

- **Decision:** Idle catch-up (`restoredAlreadyIdle` after a watch first
  sighting) is opt-in per watch via `allowIdleCatchUp` on the internal
  `WatchRecord`. It is enabled for prompt-sent watches, watches armed on a
  focused busy/retry sighting, watches armed on leave-while-busy, and watches
  restored from persistence. It is disabled for the speculative
  `focused-status-event` watch, because an idle session is often absent from
  the status map and catch-up would re-stamp Ready after the user left the
  session they opened to clear it.
- **Why:** Restart and leave-while-busy catch-up are real “this device was
  waiting” cases; a bare `session.status` before the debounced reload reveals
  the turn type is not one. Narrowing to “prompt-sent + restored” would miss
  the focused-busy and leave-while-busy turns the focused watch was added for.
- **Evidence:** OPE-110 bug: open Ready session → focused `session.status`
  watch → leave → idle rows absent from status map → Ready re-stamped.
  Regression tests `openReady_thenStatusEvent_thenLeave_doesNotRestampReady`
  on both platforms.

## Ready survives a later busy turn until focus

- **Decision:** When a session is already in the Ready set and later becomes
  `busy` / `retry` again, **leave Ready set**. Do not clear Ready on busy.
  Home row mapping still shows Running while busy (Running outranks Ready).
  Ready clears only on `setFocusedSession(that id)` or `clearCache()`.
- **Why:** Store stays simple; a missed Running mapping still recovers when the
  user opens the session. Clearing on busy would drop the “you have something
  to review” cue mid-turn without user acknowledgment.
- **Rejected:** Clear Ready when busy starts.

## `clearCache` on Clear Data and profile apply

- **Decision:** Call `SessionReadyStore.clearCache()` from Clear Data next to
  other LiveStores (`SettingsViewModel` / iOS `AppContainer` clear path). Also
  call it when a Connection Profile is saved/applied (`profileSaved`), even
  though today’s other LiveStores primarily clear on Clear Data only.
- **Why:** Ready is a sticky home cue; directory-scoped ids could otherwise
  leak across servers after a profile switch. Clear Data must not leave Ready
  residue either.
- **Evidence:** Clear Data already clears `SessionStatusLiveStore`,
  `PermissionLiveStore`, `SessionTodoLiveStore`, etc. Profile save refreshes
  home but does not clear those stores today — Ready still needs an explicit
  clear on profile apply for product isolation.

## Android Session Chat focus clears on screen dispose

- **Decision:** Android calls `setFocusedSession(null)` from
  `SessionChatScreen` `DisposableEffect`/`onDispose` (and still from
  `onCleared`), not only when the ViewModel is destroyed.
- **Why:** Navigation keeps the chat ViewModel on the back stack after
  returning home, so `onCleared` never ran and idle was treated as
  `suppressed-open` while the user was already on home. iOS already clears on
  `stopObserving`/`onDisappear`.
- **Evidence:** Android log `idle handled outcome=suppressed-open` after prompt
  watch, with no prior focus-clear, while iOS logged `outcome=ready` for the
  same turn.

- **Decision:** While Session Chat is focused, a `session.status` SSE for that
  session starts a watch immediately (before debounced status-map reload).
  `session.idle` for a watched session applies Ready/suppress using the same
  focus rules. Leaving focus while last known status is busy also arms watch.
- **Why:** Status LiveStore debounces reloads (~300 ms). A busy→idle pair can
  coalesce so Ready never sees a busy snapshot. Android send used
  `notifyPromptSent` so it still worked; iOS focused-only path did not.
- **Rejected:** Relying only on debounced status-map diffs for focused-busy.

- **Decision:** OPE-105 description Solution / Remaining / Implementation
  Decisions use **focused Session Chat** watch wording, deferred needs-approval
  shipping, `success` tone, Ready persistence with 24h TTL, and design-system
  before home mapping. Keep triage label `ready-for-agent`; do not set In
  Progress until implementation starts.
- **Why:** Agent work needs one contract. Early ticket “foreground-connected”
  busy watch is superseded by the focused-chat lock.
