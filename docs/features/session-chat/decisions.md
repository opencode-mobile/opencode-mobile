# Session Chat Decisions

## Use eager SwiftUI timeline before UIKit-backed iOS timeline

- Date: 2026-06-29
- Decision: iOS Session Chat temporarily renders the message timeline with
  eager SwiftUI `VStack` layout and `ScrollViewReader.scrollTo(lastMessage,
  anchor: .bottom)` for the scroll-to-end action pill. Do not reintroduce
  `LazyVStack` for this interaction.
- Reason: `LazyVStack` estimates off-screen variable-height rows. Session Chat
  rows can be markdown-heavy and may remeasure after first layout, so
  SwiftUI programmatic scrolling can miss the last message or land in blank
  estimated space. Eager layout is less scalable but gives deterministic
  target geometry for the current product slice.
- Follow-up: replace the iOS timeline with a UIKit-backed virtualized list
  (`UICollectionView` or `UITableView` with SwiftUI-hosted cells) before
  treating long-history performance as production-ready.
- Revisit when: the UIKit-backed timeline lands, or benchmark evidence shows
  eager SwiftUI layout is acceptable for representative long histories.

## Composite Session Chat UiState

- Date: 2026-06-27
- Decision: Session Chat Screen Models expose one composite `SessionChatUiState`
  snapshot with a regional `SessionChatTimelineState` enum for the message list and
  nested `SessionChatComposerState` for prompt controls on both platforms.
- Reason: the chat shell stays visible while the timeline and live metadata
  change independently. A root screen-phase enum duplicated identity fields and
  forced enum-case helpers for routine updates.
- Revisit when: another session-scoped screen needs a shared timeline-state
  primitive in the design-system docs.

## Start with local Screen Model scaffold

- Date: 2026-05-07
- Decision: first Session Chat implementation uses local/mock state for messages
  and composer actions on both platforms.
- Reason: unblock session-to-chat navigation and shared interaction framing before
  server integration is introduced.
- Revisit when: session history, prompt send, and stream endpoints are connected.

## Use local composer selectors in scaffold

- Date: 2026-05-09 (interaction clarified 2026-05-10)
- Decision: Session Chat composer shows local selector chips for model, mode, and
  Thinking Effort before real prompt delivery is connected. Tapping a chip opens
  **`OpenCodeBottomSheet`** to pick an explicit value instead of cycling options in
  place.
- Reason: the composer needs to reserve the product shape for next-prompt
  controls while keeping this slice server-independent.
- Revisited: 2026-05-30 by [Model Selection](../model-selection/plan.md).
  Model and Variant are now server-backed; agent mode remains a local selector.

## Keep attachment as visual affordance only

- Date: 2026-05-09
- Decision: the composer includes a `+` attachment affordance labeled as
  `Attach context`, but it does not open a real attachment workflow in this
  scaffold.
- Reason: placement matters for the chat composer, but real attachment behavior
  may imply file upload or mobile IDE capabilities that remain outside this
  slice.
- Revisit when: context attachment behavior is explicitly designed within v1
  scope.

## WhatsApp-style timeline order and scroll anchor

- Date: 2026-05-24
- Decision: Session Chat lists messages oldest-to-newest (`time.created` ascending,
  then message id). Opening a session or receiving a new bottom message scrolls
  to the latest entry. **Follow Latest** keeps the viewport pinned to the bottom
  while the viewer is already near the bottom, including while the latest
  assistant message grows through streaming updates. Pull-to-refresh does not
  jump when the latest message is unchanged.
- Reason: chat products anchor on the most recent message at the bottom; server
  order is usually chronological but the apps enforce sort and scroll explicitly.
- Superseded (2026-07-03): the automatic **Follow Latest** scroll behavior was
  removed. See **Remove automatic Follow Latest scroll for now** below. Timeline
  order (oldest-to-newest) and the manual scroll-to-end pill are unchanged.
- Revisit when: read-position preservation is needed while the user has scrolled
  up into history, or when a visible "jump to latest" control is added.

## Remove automatic Follow Latest scroll for now

- Date: 2026-07-03
- Decision: Session Chat no longer auto-scrolls the timeline to the newest
  message. Both platforms removed the reactive follow behavior
  (`shouldFollowLatestTimeline` on Android; the `onChange` message
  count/text/`isStreaming` handlers and `followLatestIfNeeded`/`isNearBottom` on
  iOS). The manual scroll-to-end action pill (`OpenCodeActionPill`) is retained
  and remains the only way to jump to the latest message.
- Consequence: opening a session and receiving new/streaming messages does not
  move the viewport. The user pulls to the bottom with the pill. This is an
  accepted interim behavior, not the target UX.
- Reason: the automatic follow behavior was unreliable across platforms and
  scroll backends (see the iOS sub-18 gap below); we are deferring it rather
  than shipping inconsistent auto-scroll.
- Superseded (2026-07-06): the pinned-bottom auto-follow prototype was promoted
  from the Component Gallery into production Session Chat. See **Reintroduce
  pinned-bottom Follow Latest** below.

## Reintroduce pinned-bottom Follow Latest

- Date: 2026-07-06
- Decision: Session Chat reintroduces automatic follow for newly appended bottom
  messages on both platforms. The viewport follows only while the user is
  already pinned near the bottom; scrolling into history disarms follow, and
  tapping the manual scroll-to-end pill re-arms follow after the programmatic
  scroll settles.
- Consequence: new messages keep an active bottom-pinned chat readable without
  requiring repeated pill taps, while older-history reading is preserved.
- Reason: the OPE-30 Component Gallery prototype proved the smaller state model:
  track whether the current message count is pinned, follow only on count
  increases, and re-check the bottom anchor after layout/render settles.
- Revisit when: streaming updates mutate the latest row without increasing the
  message count, or when iOS 17 needs a more precise near-bottom signal than the
  current `scrollPosition(id:)` fallback.

## iOS scroll-to-end detection is degraded below iOS 18

- Date: 2026-07-03
- Status: known limitation, documented (no fix planned in this slice).
- Detail: the iOS deployment target is 17.6, but distance-from-bottom and
  user-scroll detection (`ScrollToEndVisibility.shouldShow`, the
  `onScrollGeometryChange` observer) only run on iOS 18+. On iOS 17 the
  scroll-to-end pill falls back to a coarse `scrollPosition(id:)` check and
  `isUserScrolling` only flips when the scroll anchor id changes rather than
  during an active drag. The pill can therefore appear or hide at the wrong time
  on iOS 17.
- Reason: `onScrollGeometryChange` is iOS 18+. A precise iOS 17 fallback needs a
  UIKit-backed scroll observer, which is out of scope for the current slice and
  overlaps the planned UIKit-backed timeline (see the eager SwiftUI timeline
  decision above).
- Revisit when: the UIKit-backed iOS timeline lands, or Follow Latest is
  reintroduced and needs a reliable iOS 17 near-bottom signal.

## Promote Session status bar to Component Collection

- Date: 2026-06-01
- Decision: Session Chat uses the shared `OpenCodeSessionStatusBar` component
  for copyable session id, foreground SSE status, and context usage.
- Reason: the status cluster is needed on both platforms, has three explicit
  actions, and is likely to recur on future session-scoped screens.
- Revisit when: another screen needs a materially different status arrangement.

## Treat cold rich-history loading as a staged performance problem

- Date: 2026-06-24
- Decision: do not publish unstyled raw Markdown merely to shorten the loading
  indicator. Instead, retain native syntax styling and investigate staged
  rendering at the document/block level.
- Reason: Big Session profiling shows history retrieval is fast while KMP
  markdown document preparation (TextMate tokenization of fenced code blocks)
  is expensive. See `docs/features/native-rendered-markdown/progress.md`
  (Performance Profiling Synthesis) and `parity-gaps.md` (Markdown Parse
  Performance). Raw-first publishing made the app appear loaded but left it
  unresponsive and visually degraded.
- Measurement: use app-emitted benchmark probes for history request, Markdown
  preparation, and styled-timeline publication; XCTest accessibility waits are
  not reliable while the renderer is busy.
- Superseded: the 2026-06-24 iOS-only **Load earlier messages** window
  (`initialHistoryWindowSize = 3`) was removed on 2026-06-27. Session Chat on
  both platforms now enriches the full merged timeline on load; performance work
  continues one optimization at a time (JSON bridge, virtualization, etc.).
- Revisit when: code-block virtualization has been benchmarked against Big
  Session and a representative deterministic fixture is available for CI.

## Enrich full session history on load (Android parity)

- Date: 2026-06-27
- Decision: iOS Session Chat enriches the full merged timeline on load, matching
  Android. Do not gate older messages behind manual hydration.
- Reason: staged hydration improved cold-open time but diverged product behavior
  and hid history behind an extra action. Optimize performance without splitting
  the visible timeline.
- Revisit when: a shared virtualization or block-collapse strategy ships on both
  platforms with benchmark evidence.

## Split Timeline and Composer screen regions

- Date: 2026-06-27
- Decision: Session Chat Screen Models on both platforms delegate to
  `SessionChatTimeline` (message list load, live updates, markdown transform,
  optimistic rows), `SessionChatComposer` (catalog load, picker state, send,
  abort, model-selection cache), and `SessionInfo` (session id/title, SSE
  connection, agent status, context usage). `SessionChatViewModel` remains a
  **thin facade**: `bind()` orchestration, composite `SessionChatUiState` via
  `mergeUiState()`, and explicit cross-region wiring (for example
  `sendPrompt` callbacks).
- Reason: the monolithic ViewModel mixed independent concerns; parallel catalog
  and message loads are easier to reason about when each region owns its API
  calls.
- Composer catalog load uses `GET /config/providers` only. Initial model
  selection resolves inside `SessionChatComposer` (cached selection, then
  timeline `LatestModelHint`, then catalog default). The facade may pass the
  timeline hint into the composer but must not own cache state or store I/O.
- Revisit when: the experiment is evaluated; optional follow-up to drop history
  hint and use `/session` metadata or server default only.

## Keep SessionChatViewModel as a thin facade

- Date: 2026-06-28
- Decision: treat `SessionChatViewModel` as a **coordinator**, not a feature
  brain. New Session Chat work must land in a region class first. The facade
  grows only when wiring regions together — not when implementing behavior.
- **Region ownership**

  | Region | Owns |
  | --- | --- |
  | `SessionChatTimeline` | Message load/refresh, live observation, markdown rows, optimistic send merge |
  | `SessionChatComposer` | Catalog, picker state, send/abort, `SessionModelSelectionStore` read/write |
  | `SessionInfo` | Session id/title, SSE connection, agent status, context usage |
  | `SessionChatViewModel` | `bind()` orchestration, `mergeUiState()`, minimal cross-region callbacks |

- **Allowed in the facade**

  - Delegating public screen actions to the correct region.
  - `mergeUiState()` and error-priority rules that flatten regional state into
    `SessionChatUiState`.
  - Short cross-region wiring with explicit callbacks (for example
    `sendPrompt` → optimistic rows, reload, status refresh).
  - Bind bookkeeping (`sessionId`, `worktree`, `sessionTitle`) for
    orchestration only.

- **Not allowed in the facade**

  - Repository or store calls (inject dependencies into the region that uses
    them).
  - Caches, `appliedInitial*` flags, or other feature-private mutable state.
  - Growing `combine { }` collectors with side effects — prefer region methods
    or a dedicated initializer if coordination gets non-trivial.
  - Dead public methods that bypass regional ownership (remove or wire to UI).

- **Before changing `SessionChatViewModel`**

  1. Name which region owns the new behavior.
  2. If the facade must change, keep the diff to orchestration or merge logic
     only.
  3. Update this decision (or `parity-gaps.md`) when boundaries shift.
  4. Keep Android and iOS region splits aligned unless a platform difference is
     intentional and documented.

- Reason: the facade grew quickly during Message Repository and Model Selection
  integration; centralizing logic there recreated a monolith and made tests and
  parity harder. Android moved model-selection cache out of the ViewModel into
  `SessionChatComposer` on 2026-06-28; iOS should follow the same boundary.
- Revisit when: a fourth region is insufficient and a dedicated Session Chat
  coordinator type is clearer than expanding the ViewModel.

## Extract SessionInfo from facade

- Date: 2026-06-28
- Decision: Replace `SessionChatStatusBar` with `SessionInfo` for read-only
  session metadata (session id/title, SSE connection, agent work status,
  context usage). Move abort/stop into `SessionChatComposer`.
- Reason: separates passive session context from user actions (send/stop);
  `OpenCodeSessionStatusBar` remains the design-system component fed from
  flattened `SessionChatUiState`.
- Revisit when: context usage moves to a dedicated API field from the server.

## Extract SessionChatStatusBar from facade

- Date: 2026-06-27
- Superseded by **Extract SessionInfo from facade** (2026-06-28).
- Decision: Session status observation, SSE connection state, abort, and
  one-shot status refresh lived in `SessionChatStatusBar` on both platforms.

## MessageLiveStore as sole timeline message source

- Date: 2026-06-27
- Decision: `SessionChatTimeline` subscribes to `MessageLiveStore.observeMessages`
  on bind/start; it does not call `MessageRepository.listMessages` directly.
  Pull-to-refresh, post-send reload, and error retry use
  `MessageLiveStore.refreshMessages`. Initial subscribe failures propagate as
  `MessageListUpdate.LoadFailed` so the timeline can enter the error state.
  SSE-driven reconnect reloads keep the last good snapshot on silent failure.
- Reason: removes duplicate REST on screen open and the ViewModel-owned message
  observation loop on iOS; one reactive path from SSE through the live store.
- Revisit when: the server exposes push-only history with no REST seed.

## Derive context usage from best available data

- Date: 2026-06-01
- Decision: context usage uses explicit server usage if available in the future;
  otherwise it is derived from latest assistant input tokens divided by the
  relevant model context limit. Missing data renders as unavailable, not 0%.
- Reason: current app models can expose token counts and provider model context
  limits, but not every server/model response is guaranteed to include both.
- Revisit when: opencode exposes a dedicated session context-usage field.

## Use status thresholds for context pressure

- Date: 2026-06-01
- Decision: the context usage ring uses neutral icon tokens below 70%, warning at
  70%, and error at 85%.
- Reason: the status bar needs an at-a-glance pressure signal while keeping low
  usage visually quiet.
- Revisit when: product analytics or opencode server semantics define different
  context pressure bands.
