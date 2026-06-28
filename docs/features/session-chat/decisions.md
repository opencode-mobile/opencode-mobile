# Session Chat Decisions

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
- Revisit when: read-position preservation is needed while the user has scrolled
  up into history, or when a visible "jump to latest" control is added.

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
  optimistic rows) and `SessionChatComposer` (catalog load, picker state, send).
  `SessionChatViewModel` remains the facade: composite `SessionChatUiState`,
  chrome (connection, status, context usage, abort), and send wiring.
- Reason: the monolithic ViewModel mixed independent concerns; parallel catalog
  and message loads are easier to reason about when each region owns its API
  calls.
- Composer catalog load uses `GET /config/providers` only. Initial model
  selection still applies a one-shot `LatestModelHint` from timeline message
  history via the facade after both loads complete (not inside the composer
  load path).
- Revisit when: the experiment is evaluated; optional follow-up to drop history
  hint and use `/session` metadata or server default only.

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
