# Session Chat Progress

## OPE-170 Lock child session composer (2026-08-15)

- Status: implemented on both platforms (unit-tested composer kind).
- Plan: [ope-170-plan.md](./ope-170-plan.md)
- Done:
  - Session Composer `childLockout` state: locked model/effort chips, caption,
    primary Back to main session.
  - Child navigation seeds `parentID` / `parentId`; lockout wins over prompt.
  - Permission and question still replace the lockout.
- Remaining: device smoke (open a child session → no prompt; back returns to
  parent; pending permission/question still appear).

## OPE-167 top overlay pills (2026-08-15)

- Status: implemented on iOS and Android (unit-tested visibility).
- Plan: [ope-167-plan.md](./ope-167-plan.md)
- Done:
  - Top overlay: Syncing, then Todos, then Review (8pt, top-center + 10pt).
  - Bottom overlay: Scroll to end only.
  - Review shows on latest-turn file changes; not XOR with Scroll; not pin-gated.
  - Hide Todos + Review while the Session Todos sheet is open; Syncing may stay.
- Remaining: device smoke (pinned last message readable; scrolled shows Scroll
  to end at bottom and Review at top; sheet hides Todos/Review).

## OPE-145 Android scroll-to-end pill blink (2026-08-14)

- Status: implemented on Android (unit-tested). iOS already hid on user scroll
  and programmatic settle.
- Plan: [ope-145-plan.md](./ope-145-plan.md)
- Done:
  - Pill hides on any non-programmatic `isScrollInProgress` (not NestedScroll
    UserInput, which was missing drag events). Programmatic auto-follow still
    uses the settle gate instead of that flag.
  - Pill stays hidden while snap-to-end / armed unpinned follow is settling.
  - 16dp hide slack so reveal-threshold jitter cannot blink the pill.
- Remaining: device smoke (scroll away and stop → pill stays; fling to end →
  no flash; streaming auto-follow → no flicker; Scroll XOR Review still holds).

## OPE-138 Dismiss keyboard on send (2026-08-10)

- Status: implemented on both platforms in `OpenCodeSessionComposer`.
- Send clears composer focus / resigns first responder before the send action
  so the IME dismisses (OPE-62 chat policy restored after mention-composer
  refactors dropped it).
- Remaining: device smoke (focus composer → send → keyboard hides; timeline
  drag dismiss still works).

## OPE-113 Session working indicator (2026-08-07)

- Status: implemented on both platforms (unit-tested Android + iOS).
- Plan: [ope-113-plan.md](./ope-113-plan.md)
- Research: [research-session-running-indicator-gap.md](./research-session-running-indicator-gap.md)
- Done:
  - `SessionStatusLiveStore` applies `session.status` / `session.idle` locally;
    GET only on bootstrap + reconnect (no empty-map wipe on Android failure).
  - Optimistic busy after composer accepts send; clears on failure, authoritative
    busy, or resolved idle; Stop / turn-active from effective Working label.
  - In-chat Working/Retrying row when busy with no assistant content yet.
  - Follow-up: stuck-optimistic fixes + SessionInfo status / LiveStore /
    Working-row gating unit tests on both platforms; intentional host-insertion
    parity recorded.
- Remaining: device smoke (send → immediate Stop + Working; mid-turn open;
  failed status refetch keeps busy on Android).

## Shared Planning

- **2026-08-08 (OPE-117):** iOS production freeze on send / model select.
  `SWIFT_DEFAULT_ACTOR_ISOLATION = MainActor` left MessageSync ingress, SSE byte
  reading, API clients, and message domain types on MainActor. Fix: mark those
  paths `nonisolated`, detach MessageSync observation, run SSE loop off main
  (publish hops back), add `chat` diagnostic logs on send. Regression:
  `MessageSyncTests.initialLoad_messageLoopDoesNotRunOnMainThread`. Manual
  TestFlight QA still open. Residual Stages 0–5:
  [ios-concurrency-freeze-remediation-plan.md](../message-store/ios-concurrency-freeze-remediation-plan.md).
- Status: complete for scaffold-first Session Chat entry.
- Shared scope: row tap opens Session Chat with local message/composer behavior.
- **2026-07-28:** Streaming UI update backpressure plan drafted (architecture
  review). See
  [streaming-ui-update-backpressure-plan.md](./streaming-ui-update-backpressure-plan.md).
  **Phases 0–5 complete (uncommitted):** baseline layoutAffecting counters;
  Android Session Chat newest-row size animation disabled; projector text-length
  no longer layout-affecting; `grewBottomRowIDs` / armed GrowBottom follow; thin
  `TimelineApply` classify seam (`decorate` / `isRequired`); iOS Required
  flush-under-drag; Android host latest-wins hold under scroll + Session Chat
  ≥3k markdown paint defer; Android region row-reuse
  (`TimelineProjector.mergeMessageItems` / iOS `reusePrevious` parity). Manual
  acceptance scenarios 1–6 still open on device.
- **2026-07-28:** iOS programmatic pin / scroll-to-end now always uses
  `setContentOffset(bottomContentOffsetY())` instead of preferring
  `scrollToItem(.bottom)` (short-of-bottom + estimate settle “fall”).
- **2026-07-28:** iOS host cell self-resize animation suppressed
  (`MessageListTimelineHostCell`) + per-row height cache / heuristic estimate
  seed (replacing flat `estimated(72)`).
- **2026-07-28:** Experimental `HOLD_READY_UNTIL_NEWEST_MARKDOWN` /
  `holdReadyUntilNewestMarkdown` (default **false**): when true, large
  histories keep Session Chat on Loading until the newest markdown candidate is
  enriched. Production path publishes format-first Ready immediately.
- **2026-07-28:** Assistant bubbles paint prepared markdown immediately on first
  appear; ≥3k paint-defer remains only for docs that arrive later on an already
  visible plain bubble.
- **2026-07-28:** Fenced code/diff paint prepared structure on first frame (no
  raw-fence flash). Later superseded for streaming/history by OPE-92
  format-always (structure-only when TextMate on; full semantic when off).
- **2026-07-28:** Fixed TextMate upgrade after stream end: `FinalPlaceholder`
  now marks `awaitingTextMateUpgrade`, and `Final` must not settle-reuse that
  streaming-quality document. Pipeline also passes the pre-placeholder snapshot
  into `Final` prepare.
- **2026-07-28:** TextMate highlighting Settings → Developer Tools gated
  (`MarkdownHighlightPreferences`). **Default later flipped to on** (Phase 6,
  2026-07-29) behind the top-ten allowlist; explicit off still uses semantic
  fallback. Toggling bumps `highlightEpoch` and clears prepared caches.
- **2026-07-31:** Current Session Chat markdown contract: format-always
  structure/chrome on streaming and large-history open; TextMate colors upgrade
  asynchronously when enabled; no raw markdown source for detectable fences.
  See `../native-rendered-markdown/ope-92-structure-first-code-blocks-plan.md`
  and `../message-store/decisions.md` (Streaming fast lane).

## Design System

- Home session rows reuse existing list-item patterns.
- Session Chat scaffold uses existing platform theme tokens and typography.
- Screen chrome uses **`OpenCodeTopBar`**: title **Session** on line 1, foreground
  SSE status on line 2 via `subtitleAccessory.statusDot`, leading **back**
  (pops the navigation stack), overflow menu, and optional bottom divider.
- Session status bar shows only the context-usage ring when token and
  model-limit data are available.
- **2026-08-03:** Session status bar is temporarily hidden on the Session screen
  (iOS + Android). Component and gallery remain; Session context stays available
  from the overflow menu. Flip `showsSessionStatusBar` /
  `ShowsSessionStatusBar` to restore.
- **2026-07-07 / OPE-31:** Moved foreground SSE connection state from
  `OpenCodeSessionStatusBar` into the Session screen two-line top bar.
- Session status bar is promoted to the shared Component Collection as
  **`OpenCodeSessionStatusBar`**.
- Session status bar Component Gallery entries include connection, availability,
  and context usage presets (`0%`, `50%`, `70%`, `85%`) for threshold review.
- **2026-07-12 / OPE-33:** Simplified **`OpenCodeOutgoingMessage`** to a
  display-only bubble (text/markdown + metadata footer). Inline restore/copy
  icons removed; Session Chat opens **`OpenCodeBottomSheet`** message actions
  instead. Outgoing messages: Revert message + Copy message. Assistant
  messages: Copy message only. Tool/command/unknown rows stay non-interactive.
  Outgoing width caps at 80% with shrink-wrap content (no min width). Pill
  padding is 12×8 for outgoing and assistant bubbles. iOS timeline overlap fix:
  outgoing message no longer uses a root `GeometryReader` that collapsed row
  height in `LazyVStack`.
- **2026-07-05 / OPE-30:** Message List Component Gallery prototype now has
  live-message and auto-follow controls on iOS and Android. When auto-follow is
  enabled and the list is already pinned to the bottom, newly appended gallery
  messages wait for layout/render and then advance to the bottom anchor. If the
  user is reading older messages, auto-follow does not pull the viewport forward.
- **2026-07-06 / OPE-30:** Promoted the tested pinned-bottom auto-follow behavior
  from the Component Gallery into production Session Chat on iOS and Android.
  The timeline follows newly appended messages only while the viewer is already
  pinned near the bottom, and tapping the scroll-to-end pill re-arms follow for
  later appends.

## Android

- Session Chat Screen Model exposes composite `SessionChatUiState` with regional
  `SessionChatTimelineState` for the message list.
- Wired session row tap from `MainScreen` to Session Chat route.
- Added Session Chat scaffold with local message list and composer send behavior.
- Replaced the standalone Session ID row with the 36 dp status bar.
- Added Session Chat Screen Model state for foreground event-stream connection
  status and context usage.
- Refined composer into prompt input, local selector chips, attachment
  affordance, and Send action rows.
- Composer selector chips open **`OpenCodeBottomSheet`** pickers instead of
  cycling values in place.
- **2026-06-27:** Refactored Screen Model into `SessionChatTimeline`,
  `SessionChatComposer`, `SessionInfo`, and a thin `SessionChatViewModel`
  facade. All `SessionChatViewModelTest` cases pass.
- **2026-06-27, amended 2026-07-26:** Timeline observation remains behind
  `MessageRepository`; the current display path uses
  `observeDisplayMessages` / `refreshDisplayMessages`, backed by `MessageSync`
  and `DisplayMessagePipeline`. No duplicate REST on bind.
- **2026-06-28:** Model-selection cache load/save and initial selection resolution
  live in `SessionChatComposer` on both platforms. See
  [Keep SessionChatViewModel as a thin facade](decisions.md#keep-sessionchatviewmodel-as-a-thin-facade).
- **2026-07-06 / OPE-30:** Production `SessionChatTimelineView` uses the shared
  auto-follow state helper and pinned-bottom detector. Appended messages advance
  to the bottom only when the user has not scrolled away from the latest message.
- **2026-07-08 / OPE-32:** Composer agent mode (`Build` / `Plan`) is now
  mapped to server agent names (`build` / `plan`) and sent with
  `POST /session/{id}/prompt_async`.
- **2026-07-13:** Native composer permission state observes pending requests,
  prioritizes the root session before descendants, and resolves allow-once or
  deny decisions with stale-request reconciliation.

## iOS

- Session Chat Screen Model exposes composite `SessionChatUiState` with regional
  `SessionChatTimelineState` for the message list.
- Wired `ProjectListHomeView` session rows with `Button` plus
  `navigationDestination(item:)` so **`List` does not show disclosure chevrons**
  (SwiftUI adds chevrons for `NavigationLink` rows by default).
- Added local message list and composer send behavior in Session Chat.
- Replaced the standalone Session ID row with the 36 pt status bar.
- Added Session Chat Screen Model state for foreground event-stream connection
  status and context usage.
- Refined composer to match Android's prompt input, local selector chips,
  attachment affordance, and Send action rows.
- Composer selector chips open **`openCodeBottomSheet`** pickers instead of
  cycling values in place.
- **2026-06-27:** Refactored Screen Model into `SessionChatTimeline`,
  `SessionChatComposer`, `SessionInfo`, and a thin `SessionChatViewModel`
  facade (mirrors Android). All `SessionChatViewModelTests` pass.
- **2026-06-27, amended 2026-07-26:** Message observation moved from ViewModel
  into Timeline through `MessageRepository.observeDisplayMessages`;
  `loadMessages()` loads catalog only (matches screen `.task` flow).
- **2026-06-28:** Model-selection cache moved from `SessionChatViewModel` into
  `SessionChatComposer` (parity with Android).
- **2026-07-06 / OPE-30:** Production `SessionChatTimelineView` uses the same
  pinned-bottom auto-follow state as the Message List gallery. Appended messages
  advance to the bottom only when the user has not scrolled away from the latest
  message.
- **2026-07-08 / OPE-32:** Composer agent mode (`Build` / `Plan`) is now
  mapped to server agent names (`build` / `plan`) and sent with
  `POST /session/{id}/prompt_async`.
- **2026-07-13:** Native composer permission state observes pending requests,
  prioritizes the root session before descendants, and resolves allow-once or
  deny decisions with stale-request reconciliation.
- **2026-07-20 / OPE-67:** Session Chat and Message List gallery use
  `MessageListTimelineHost` (`UICollectionView` + SwiftUI-hosted cells) with
  `TimelineScrollController` for cancellable coalesced follow and
  distance-from-bottom pin detection. Removed the SwiftUI `LazyVStack` +
  `scrollPosition` two-step snap path.
- **2026-07-30 / OPE-93:** Session Chat owns keyboard avoidance via
  `keyboardOverlapPadding()` so the composer stays above the software keyboard
  on device. The UIKit timeline host plus `UITextView` composer defeat SwiftUI's
  automatic keyboard safe area; Android already uses `.imePadding()`.

## Architecture guardrails

- Session Chat Screen Model changes are **high-risk**. Read
  [Keep SessionChatViewModel as a thin facade](decisions.md#keep-sessionchatviewmodel-as-a-thin-facade)
  before editing `SessionChatViewModel` on either platform.

## Behavior hardening

- Production sticky-bottom follow handles markdown/render growth, streaming row
  height changes, composer/keyboard viewport changes, and status/notice rows
  while follow is armed.
- **2026-07-26 / OPE-82:** Large markdown histories now publish the complete
  plain-text history first and enrich markdown newest-first without blocking
  live snapshots. Streaming display snapshots coalesce to the newest available
  frame while explicit refresh and post-send completion publications remain
  ordered and non-droppable.
- **2026-07-20 / OPE-67 (iOS):** Follow is driven by `UIScrollView` content-size
  growth and message appends through one cancellable scroll-to-end job. iOS no
  longer uses `layoutSignature` or `scrollPosition(id:)` for auto-follow.
- Android scroll math includes the visible tail of the newest row, bottom
  content padding, and the bottom anchor. The pill waits until the true
  distance-from-end threshold is crossed even if the anchor has just left the
  viewport, and the initial view still performs the same one-time
  scroll-to-latest as iOS.
- Production Session Chat uses non-animated exact end correction for layout
  growth, viewport shrink while armed, and message appends; only the manual
  scroll-to-end pill animates.
- Android observes IME-driven viewport height changes and re-anchors while
  follow is armed.
- `OpenCodeOutgoingMessage` always renders outgoing prompts at full height so
  uncertain collapse height does not destabilize follow behavior.
- Temporary scroll diagnostics remain available through Android
  `SessionChatScroll` logs and the iOS `session-chat` logger category.
- Composer selector chips open bottom sheets for model, agent mode, and Variant.
- **2026-07-16 / OPE-55:** Native Photos and Files/document pickers, removable
  pending chips, file-part prompt send, and outgoing attachment rows shipped on
  iOS and Android.

## Remaining Work

- Dedicated diff viewing remains separate feature work. Real attachment handling
  is delivered under [File Attachments](../file-attachments/progress.md).

## Big Session Performance Investigation

- Fixture: the manually named **Big Session** on the local opencode server. It
  contains a large syntax-highlighted code response and is used only as a local
  performance repro, not as a deterministic CI fixture.
- [`scripts/analyze-ios-stream-pacing.py`](../../../scripts/analyze-ios-stream-pacing.py)
  scores `MessageContentPreparer` publish logs for long live replies. It requires
  late-stream samples at or above 15,000 characters and fails when their p90
  publish gap exceeds 120 ms.

### Last measured baseline (2026-06-27, libonig + full-history enrichment)

- Manual device test: **~2.5 s** cold open to styled timeline on iOS, matching
  Android for the same session.
- iOS and Android both enrich the full merged timeline on load.
- Warm revisits remain fast because the in-memory render-document cache is keyed
  by message text.
- OPE-82 staged large cold histories (plain-first, then newest-first
  enrichment) on 2026-07-26. OPE-92 (2026-07-30) replaced the plain-source
  first paint with format-always structure/semantic scaffolds; enrichment still
  upgrades TextMate colors newest-first when enabled. No replacement cold-open
  measurement has been recorded yet, so the ~2.5 s result above is retained as
  historical baseline, not claimed for the staged pipeline.

### Historical (2026-06-24, hybrid regex backend, staged window)

- Measurement: opt-in iOS UI benchmark via Darwin-notification probes.
- Cold-path breakdown:

  | Stage | Elapsed from benchmark start |
  | --- | ---: |
  | Session screen load started | 2.940 s |
  | Full history request started | 2.944 s |
  | Full history request finished | 3.061 s |
  | Markdown preparation started | 3.082 s |
  | Markdown preparation finished / styled timeline published | 27.303 s |

- Finding: history fetch **0.118 s**; markdown preparation for the initial
  visible window **24.221 s**. Bottleneck was KMP TextMate tokenization on the
  non-Oniguruma iOS regex shim.

### Markdown hot path

`DisplayMessagePipeline` → `MessageContentPreparer` →
`OpenCodeMarkdownParser.parse()` (Android) / JSON bridge (iOS) →
`parseFence()` → `OpenCodeSyntaxHighlighter.highlight()` →
`OpenCodeTextMateNativeTokenizer.tokenize()`.

The parser/tokenizer portion is unchanged from the earlier Screen Model /
`RenderedMarkdownMapper` benchmark path; OPE-82 moved orchestration and
publication ownership into the domain display pipeline.

### Experiments tried

- removing the duplicate foreground history fetch while the live store reloads;
- cache-first history publication;
- moving history DTO adaptation off the main actor;
- publishing raw/unstyled rows followed by one blocking full-history render
  (rejected: unresponsive and degraded styling);
- publishing the complete plain-text history followed by cancellable,
  newest-first incremental enrichment (shipped in OPE-82);
- preparing only a latest-message window (rejected 2026-06-27: Android parity);
- **libonig backend** (shipped): restored acceptable Big Session cold open.

### Next optimization candidates

- virtualize large code-block styling or layout without hiding source lines;
- slim or bypass the iOS JSON bridge if it shows up on other profiles.
- capture a deterministic cold-open measurement for the staged history
  pipeline.
