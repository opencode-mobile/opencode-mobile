# Session Chat Decisions

## User-row metadata survives live-then-REST (2026-08-17)

- Date: 2026-08-17
- Decision: iOS timeline return-previous must compare `metadataLabel` (and
  model ids / context tokens) like Android `structurallyEqual`. Empty vs
  non-empty metadata is layout-affecting. When an optimistic user row
  reconciles to a live inbox row that still has no mode/model/time, copy the
  pending caption onto that server row. Live `session.inbox.enqueued` also
  projects envelope `created` onto user `info.time.created`.
- Reason: v2 inbox paints a sparse `{id, role}` user message first. iOS then
  reused that row after REST filled `build · model · variant · time`, so the
  caption never appeared. Android already rebuilt when the label changed.
- Revisit when: live inbox events carry agent/model themselves.

## OPE-170 child sessions cannot be prompted

- Date: 2026-08-15
- Decision: A child session replaces prompt entry with Session Composer
  `childLockout`: display-only model and thinking-effort chips, the caption
  “Child sessions cannot be prompted.”, and a high-contrast
  “Back to main session” button. Permission and question still replace that
  state. Child navigation passes the parent session id so the lockout appears
  immediately.
- Reason: Web already blocks prompting in a child session. Mobile had the same
  composer as a root session. Prototype variant A kept the composer shell and
  made back the primary action.
- Evidence: [ope-170-plan.md](./ope-170-plan.md)
- Revisit when: mobile should send follow-up prompts into a child session.

## OPE-169 promote assistant bubble; keep paint deferral local

- Date: 2026-08-15
- Decision: Promote the Session Chat assistant text bubble to
  `OpenCodeAssistantMessage`. Session Chat keeps message-action gestures and
  the ≥3k late-markdown paint deferral in a thin wrapper, passing
  `renderedMarkdown` only when it is ready to paint.
- Reason: Outgoing Message is already a collection primitive; the assistant
  card is the matching incoming chrome and was duplicated by snapshot-evidence
  hosts. Paint deferral is a Session Chat performance seam, not component
  chrome.
- Evidence: [ope-169-plan.md](./ope-169-plan.md)
- Revisit when: another surface needs the same late-markdown deferral.

## OPE-167 top overlay stack; Scroll to end stays at the bottom

- Date: 2026-08-15
- Decision: Session Todos, Review Changes, and Syncing overlay the **top** of
  the Session Chat timeline (Syncing, then Todos, then Review; 8pt gap;
  top-center + 10pt). Only Scroll to end stays above the composer. Review
  Changes is no longer XOR with Scroll to end and is not gated on pin — it
  shows whenever the latest completed turn changed at least one file. Hide
  Todos and Review while the Session Todos sheet is open; Syncing may stay.
  Timeline pin / fade inset stays composer + 16.
- Reason: The bottom stack covered the last message. Prototype variant A
  (`docs/prototypes/pills-on-top/`) keeps the same capsules and leaves only
  the scroll action at the bottom.
- Evidence: [ope-167-plan.md](./ope-167-plan.md)
- Revisit when: a reserved strip or top-bar fold is preferred over the overlay.

## OPE-145 Android scroll-to-end pill uses user-scroll and settle gates

- Date: 2026-08-14
- Decision: Android Scroll to end visibility hides during user drag/fling and
  while the timeline is settling to end (programmatic snap or armed unpinned
  follow). Distance uses the existing reveal threshold with 16dp hide slack.
  Session Chat still mounts the pill without fade.
- Reason: `isScrollInProgress` is true for auto-follow as well as user input, so
  a fling that stops near the end flashed the pill on settle then hid it again
  when follow snapped. Fade was rejected because OPE-106 `spacedBy` ghost gaps
  return if invisible pills stay composed.
- Evidence: [ope-145-plan.md](./ope-145-plan.md)
- Revisit when: iOS needs the same hide slack, or a fade can be restored without
  reintroducing float-stack gaps.

## OPE-113 immediate status + optimistic busy + Working row

- Date: 2026-08-07
- Decision: Match web `session_working` feedback by (1) applying SSE
  `session.status` / `session.idle` payloads in `SessionStatusLiveStore`
  immediately, keeping GET for bootstrap/reconnect only; (2) setting optimistic
  busy only after the composer accepts a send (not before early returns), and
  clearing it on send failure, authoritative busy, or resolved idle; (3) showing
  a feature-local muted Working/Retrying timeline row when busy with no assistant
  content yet — not a design-system shimmer and not web’s tab progress dots.
- Reason: Stop alone lagged behind HTTP accept and offered no secondary chrome;
  Android emptyMap-on-failure could also clear busy mid-turn. Marking busy before
  composer validation could leave Stop/Working stuck on no-op sends; keeping
  optimistic across resolved idle did the same after a fast turn.
- Evidence: [research-session-running-indicator-gap.md](./research-session-running-indicator-gap.md),
  [ope-113-plan.md](./ope-113-plan.md)
- Revisit when: product wants web-style progress-dot chrome or a shared shimmer
  primitive.

## OPE-113 Working-row host insertion (intentional)

- Date: 2026-08-07
- Decision: Keep platform-native timeline insertion for the Working/Retrying row.
  iOS appends a synthetic assistant `SessionChatRow` and special-cases its id in
  the host; Android uses a dedicated LazyColumn footer slot on
  `MessageListTimeline`. Shared product gating stays in `WorkingTurnIndicator`.
- Reason: Each host already owns list identity and apply-packet rules; forcing one
  insertion model would fight existing scroll/apply seams without a user-visible
  gain.
- Revisit when: a shared timeline host abstraction lands.

## Working row stays after completed tools while SSE says busy (2026-08-17)

- Date: 2026-08-17
- Decision: Hide the in-chat Working/Retrying row only for in-progress assistant
  activity after the last user message (streaming text, pending/running tools).
  Completed tools do not hide it. `session.active.snapshot` GET that returns an
  empty active map does not clear SSE-applied busy; true idle stays
  `session.idle` / `session.execution.*`.
- Reason: v2 turns are multi-step. After a finished shell card, Android hid
  Working even while SSE still reported busy. Step-end snapshots can briefly
  return `{}` and were wiping that busy bit.
- Revisit when: product wants Working hidden whenever any assistant chrome exists.

## Ship Android region row-reuse (Phase 4)

- Date: 2026-07-28
- Decision: Implement Android `TimelineProjector.mergeMessageItems` mirroring
  iOS `reusePrevious` in `SessionChatTimeline.publishDisplayMessages` without
  waiting for device measurement. Still publish a full message list each frame;
  reuse object identity for unchanged non-pending rows. Do not reopen parked
  public `DisplayFrame` observation or introduce a host patch protocol.
- Reason: Seam was already clear; remaining cost was unproven but the change is
  small, region-only, and restores cross-platform reuse parity.
- Plan: [streaming-ui-update-backpressure-plan.md](./streaming-ui-update-backpressure-plan.md)
- Revisit when: reuse incorrectly keeps a stale row, or pending/insert paths
  fail to rebuild.

## Defer Phase 4 patch / row-reuse until measured

- Date: 2026-07-28
- Decision: ~~Do not implement Phase 4 until device acceptance fails or traces
  show O(n) remapping as a hotspot.~~ **Superseded the same day** by
  **Ship Android region row-reuse (Phase 4)** after an explicit product choice
  to implement without waiting on measurement.
- Reason: Original deferral was a whether-to-ship gate, not an unknown how.
- Plan: [streaming-ui-update-backpressure-plan.md](./streaming-ui-update-backpressure-plan.md)
- Revisit when: n/a — superseded.

## Separate content and layout revisions for streaming timeline updates

- Date: 2026-07-28
- Decision: Session Chat timeline projection must keep two revision planes.
  `contentRevision` may change every streaming frame; `layoutRevision` changes
  only when geometry policy says so (structure, real height growth of the
  newest row, height-changing decoration, streaming→final). Streaming
  `textLength` alone must not automatically mark a frame layout-affecting.
  Newest-row streaming growth uses an explicit `GrowBottom` (or equivalent)
  layout intent: pin if auto-follow is armed; do not run unarmed visible-anchor
  restore on pure Content updates. Do not animate row height while streaming on
  either platform. Scroll controllers react to declared layout intents, not
  every Screen Model republish. Required load / refresh / send applies never
  coalesce away.
- Reason: domain display coalescing already bounds SSE publish rate; scroll
  jank under stream comes from host remeasure / rebind / follow coupling when
  every length delta is treated as layout-affecting (especially Android
  `animateContentSize` on the newest reverse-`LazyColumn` row).
- Plan: [streaming-ui-update-backpressure-plan.md](./streaming-ui-update-backpressure-plan.md)
- Revisit when: host-observed GrowBottom under-follows in production, or
  Instruments shows remaining hitch after Phases 1–3 and Phase 4 patch updates
  are warranted.

## Use thin TimelineApply classify seam (not host rewrite)

- Date: 2026-07-28
- Decision: Phase 2 introduces a thin `TimelineApply` / `TimelineApplyKind`
  classify seam derived from `TimelineFrame` ID sets plus region `isRequired`.
  Do not rewrite Diffable or Compose invalidation around kinds. Keep
  `Structure`, `Content`, `GrowBottom`, `Decorate`, and `Required` as a
  multi-kind set. Partition markdown-only layout bumps into `decorateRowIDs`
  (streaming end remains Structure). iOS Required applies flush immediately
  under drag; Decorate stays layout-affecting for follow revisions; Phase 3
  Android hold pends non-Required kinds (including Decorate) under scroll.
- Reason: Phase 1 already delivered the scroll-policy split; hosts need kinds
  for logs and Phase 3 policy without boiling list hosts.
- Plan: [streaming-ui-update-backpressure-plan.md](./streaming-ui-update-backpressure-plan.md)
- Revisit when: kinds prove insufficient for further host policy, or Phase 4
  patch updates need finer invalidation than classify provides.

## Android host latest-wins hold + Session Chat markdown defer

- Date: 2026-07-28
- Decision: Production Android Session Chat holds `messages` + `timelineFrame`
  while `LazyListState.isScrollInProgress`, flushing only Required frames
  immediately (`MessageListTimelineApplyHold`). Large assistant markdown
  (≥3000 chars) paints plain first, then yield + ~32 ms
  (`SessionChatMarkdownPaint`). Policy stays view-owned; gallery unchanged;
  vsync gate stays out.
- Reason: Match iOS drag pending-apply and large-markdown paint defer without
  pushing scroll awareness into the Screen Model or region publisher.
- Plan: [streaming-ui-update-backpressure-plan.md](./streaming-ui-update-backpressure-plan.md)
- Revisit when: hold drops Structure on settle, Required sticks under fling, or
  deferred markdown causes a jarring height jump after stream end.

## Suppress iOS self-resize animation + seed row height estimates

- Date: 2026-07-28
- Decision: Session Chat / Message List host cells use
  `MessageListTimelineHostCell` to wrap `apply(_:)` and
  `preferredLayoutAttributesFitting` in disabled CALayer actions so UIKit's
  default animated self-resize (WWDC22) snaps instead of sliding. Seed
  compositional `.estimated` height from a per-id last-known height cache with
  role/length heuristics (`MessageListTimelineHeightEstimating`); keep
  `selfSizingInvalidation = .enabled` (not including constraints). Do not call
  `layoutIfNeeded` on pin/apply paths. Escalation (CALayer `action(for:)`, custom
  layout) only if device still shows visible fall after this slice.
- Reason: Diffable `animatingDifferences: false` and SwiftUI transaction nil do
  not wrap UIKit's internal estimate→real frame mutation; flat `estimated(72)`
  maximized the visible correction.
- Revisit when: acceptance still shows animated settle, or intrinsic height
  thrash requires SwiftUI-side stabilization.

## Pin iOS timeline with absolute bottom content offset

- Date: 2026-07-28
- Decision: Programmatic pin / scroll-to-end / re-pin on iOS
  `MessageListTimelineHost` always uses
  `setContentOffset(y: bottomContentOffsetY())` (contentSize − viewport +
  `adjustedContentInset.bottom`). Do not prefer `scrollToItem(last, .bottom)`.
  Keep section `bottomContentPadding` as the sole bottom padding; do not bake
  padding into the last message cell. Do not add a trailing bottom-anchor spacer
  unless absolute-offset pinning still fails acceptance after estimate retries.
- Reason: `scrollToItem(.bottom)` targets estimated last-cell frames and lands
  short of section bottom inset / true scrollable extent, causing short-of-bottom
  opens and visible estimate→self-size “falling.” OPE-67 already chose absolute
  bottom offset math.
- Revisit when: device acceptance still shows short-of-bottom after retries, or
  a fixed trailing spacer (Android-like anchor) is warranted.

## Use UIKit-backed virtualized iOS timeline

- Date: 2026-07-20
- Ticket: OPE-67
- Decision: iOS Session Chat renders the message timeline with a UIKit
  `UICollectionView` host (`MessageListTimelineHost`) and SwiftUI-hosted cells.
  Programmatic scroll and pin detection live in `TimelineScrollController`: one
  cancellable in-flight scroll-to-end job, coalesced follow on content growth /
  append while armed, and distance-from-bottom pin math from `UIScrollView`.
  Do not use SwiftUI `ScrollView` + `LazyVStack` + `scrollPosition(id:)` for
  Session Chat auto-follow.
- Reason: SwiftUI `LazyVStack` estimates off-screen variable-height rows, so
  programmatic scrolling can miss the last message or land in blank estimated
  space. The previous `scrollPosition` two-step (nil → last message → bottom
  anchor) with overlapping `Task` yields caused clanky auto-follow during
  streaming. UIKit virtualization plus absolute bottom `setContentOffset`
  gives reliable end anchoring and cancels overlapping follow settle work.
- Supersedes: **Use eager SwiftUI timeline before UIKit-backed iOS timeline**
  (2026-06-29) and the interim LazyVStack + `scrollPosition` production path.
- Revisit when: Instruments shows hosted SwiftUI cells still hitch on Big
  Session and block-level virtualization is warranted.

## Use eager SwiftUI timeline before UIKit-backed iOS timeline

- Date: 2026-06-29
- Status: superseded (2026-07-20) by **Use UIKit-backed virtualized iOS timeline**
  (OPE-67).
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
- Revisited: 2026-07-16 — device **Attachments** are specified under
  [File Attachments](../file-attachments/plan.md) with glossary terms in
  `CONTEXT.md`.
- Superseded: 2026-07-16 by OPE-55. The affordance now opens native Photos and
  Files/document entry points and uses **Add attachment**. Project `@` /
  `file://` browsing remains out of scope.

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
- Decision: Session Chat reintroduces sticky-bottom follow on both platforms.
  Opening a session lands on the latest message. After entry, the viewport
  follows newly appended bottom messages, last-row growth from streaming or
  markdown rendering, and timeline viewport changes while the user is already
  pinned near the bottom. Scrolling into history disarms follow, and tapping the
  manual scroll-to-end pill re-arms follow after the programmatic scroll settles.
- Consequence: new messages keep an active bottom-pinned chat readable without
  requiring repeated pill taps, while older-history reading is preserved.
- Reason: the OPE-30 Component Gallery prototype proved the smaller state model:
  track whether the current timeline is pinned, follow count increases and
  bottom-anchor drift while armed, and re-check the bottom anchor after
  layout/render settles. Production Session Chat needs the layout-drift branch
  because markdown rendering, streaming text, composer height, keyboard padding,
  and status/notice rows can move the bottom without changing message count.
- Revisit when: iOS 17 needs a more precise near-bottom signal than the current
  `scrollPosition(id:)` fallback.

## iOS scroll-to-end detection is degraded below iOS 18

- Date: 2026-07-03
- Status: superseded (2026-07-20) by **Use UIKit-backed virtualized iOS timeline**
  (OPE-67). Pin and pill visibility now use `UIScrollView` distance-from-bottom
  on the deployment target (17.6+).
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
- Status: superseded on 2026-07-26 by **Stage large history enrichment without
  changing history scope** below.
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

## Stage large history enrichment without changing history scope

- Date: 2026-07-26
- Ticket: OPE-82
- Decision: when the initial history contains at least 4,000 markdown-candidate
  characters, publish the complete message history immediately as native plain
  text, then enrich markdown-bearing messages newest-first. Yield between
  enriched snapshots and cancel stale enrichment as soon as a newer live
  message snapshot arrives. Smaller histories remain an all-at-once prepared
  publish.
- Reason: the earlier raw-first experiment still performed one blocking
  full-history parse before the UI became usable. The staged pipeline keeps
  every message visible, prioritizes the rows near the initial bottom position,
  and gives live deltas priority over cold-history decoration.
- Consequence: this changes presentation readiness, not history scope. Both
  platforms still expose the full merged history and converge on the same fully
  rendered result without a manual hydration control.
- Revisit when: representative device evidence supports changing the 4,000
  character gate or removing staged enrichment after parser performance
  improves.

## Enrich full session history on load (Android parity)

- Date: 2026-06-27
- Amended: 2026-07-26 by **Stage large history enrichment without changing
  history scope** above.
- Decision: iOS and Android expose the full merged timeline on load. Do not gate
  older messages behind manual hydration. Large histories may stage markdown
  presentation after every raw message row is visible.
- Reason: staged hydration improved cold-open time but diverged product behavior
  and hid history behind an extra action. Optimize performance without splitting
  the visible timeline.
- Revisit when: a shared virtualization or block-collapse strategy ships on both
  platforms with benchmark evidence.

## Coalesce replaceable live timeline frames

- Date: 2026-07-26
- Ticket: OPE-82
- Decision: while timeline transformation or UI publication is busy, live
  display snapshots may coalesce to the newest available snapshot. Explicit
  load, refresh, and post-send publications are not replaceable; their state
  transitions and completion callbacks must run in order. Rebinding or stopping
  observation cancels pending work from the previous binding.
- Reason: replaying every token-sized visual snapshot after the server has
  advanced wastes work and makes the UI lag behind, while dropping an explicit
  post-send completion can leave the composer stuck in its sending state.
- Revisit when: the display pipeline publishes structural patches instead of
  complete snapshots.

## Use distance from the true timeline end for pill visibility

- Date: 2026-07-26
- Ticket: OPE-82
- Decision: the scroll-to-end pill appears only after the viewport is farther
  than the shared near-bottom threshold from the true timeline end. Losing the
  one-pixel bottom anchor is not sufficient by itself. Distance calculations
  include the remaining visible portion of the newest row, bottom content
  padding, and the bottom anchor, including when the newest message is taller
  than the viewport.
- Reason: treating an absent anchor as immediately away from the bottom made the
  Android pill flash during small layout changes and produced incorrect results
  inside very tall streaming messages. iOS already derives visibility from
  `UIScrollView` distance from bottom.
- Revisit when: unread-count semantics replace distance-only pill visibility.

## Split Timeline and Composer screen regions

- Date: 2026-06-27
- Decision: Session Chat Screen Models on both platforms delegate to
  `SessionChatTimeline` (message list load, live updates, markdown transform,
  optimistic rows), `SessionChatComposer` (catalog load, picker state, send,
  abort, model-selection cache), `SessionInfo` (session id/title, SSE
  connection, agent status, context usage), and `SessionChatReviewChanges`
  (completed-turn eligibility and worktree-scoped diff count). `SessionChatViewModel` remains a
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
- Updated: 2026-07-19 (OPE-66)
- Decision: treat `SessionChatViewModel` as a **coordinator**, not a feature
  brain. New Session Chat work must land in a region class first. The facade
  grows only when wiring regions together — not when implementing behavior.
- **Region ownership**

  | Region | Owns |
  | --- | --- |
  | `SessionChatTimeline` | Message load/refresh, live observation, markdown rows, optimistic send merge |
  | `SessionChatComposer` | Catalog, picker state, send/abort, `SessionModelSelectionStore` read/write |
  | `SessionInfo` | Session id/title, SSE connection, agent status, context usage |
  | `SessionChatReviewChanges` | Completed-turn eligibility, diff-count lifecycle, request diagnostics |
  | `SessionChatViewModel` | `bind()` orchestration, `mergeUiState()`, minimal cross-region callbacks |

- **Exception (OPE-66):** Question Request live observation, session-tree
  selection, wizard drafts, and reply/dismiss stay on the facade next to
  Permission Approval for this slice. See
  [question-request decisions](../question-request/decisions.md) (“Keep live
  interruption wiring beside Permission Approval”). Extracting a shared
  interruption region remains an optional follow-up when both queues grow
  further.

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

## Timeline observation stays behind Message Repository

- Date: 2026-06-27
- Amended: 2026-07-26 by the Message Store refactor.
- Decision: `SessionChatTimeline` subscribes to
  `MessageRepository.observeDisplayMessages` on bind/start; it does not call a
  transport adapter directly. Pull-to-refresh, post-send reload, and error retry
  use `MessageRepository.refreshDisplayMessages`. `MessageSync` and
  `DisplayMessagePipeline` remain behind that repository boundary. Initial
  subscribe failures propagate as `DisplayMessageListUpdate.LoadFailed` so the
  timeline can enter the error state. SSE-driven reconnect reloads keep the last
  good snapshot on silent failure.
- Reason: removes duplicate REST on screen open and the ViewModel-owned message
  observation loop on iOS; one reactive path from SSE through domain
  synchronization and display preparation.
- Revisit when: the server exposes push-only history with no REST seed.

## Hide Session status bar until it has more than context usage

- Date: 2026-08-03
- Decision: Keep `OpenCodeSessionStatusBar` implemented and in the Component
  Gallery, but hide it on the Session Chat screen on both platforms for now.
- Reason: the bar currently only surfaces context usage, which is not worth a
  dedicated chrome row by itself. Session context remains reachable from the
  overflow menu.
- Revisit when: the status bar gains additional useful session chrome (or
  context usage moves to a denser placement).

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
