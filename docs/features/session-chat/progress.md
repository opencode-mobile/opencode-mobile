# Session Chat Progress

## Shared Planning

- Status: complete for scaffold-first Session Chat entry.
- Shared scope: row tap opens Session Chat with local message/composer behavior.

## Design System

- Home session rows reuse existing list-item patterns.
- Session Chat scaffold uses existing platform theme tokens and typography.
- Screen chrome uses **`OpenCodeTopBar`**: title **Session**, leading **back**
  (pops the navigation stack), optional bottom divider; session identity stays in
  a 36 dp/pt status bar under the top bar (not in the top-bar title).
- Session status bar shows copyable session id, foreground SSE state, and a
  context-usage ring when token and model-limit data are available.
- Session status bar is promoted to the shared Component Collection as
  **`OpenCodeSessionStatusBar`**.
- Session status bar Component Gallery entries include connection, availability,
  and context usage presets (`0%`, `50%`, `70%`, `85%`) for threshold review.
- Composer chips use `OpenCodeChoiceBadge`; tapping a chip opens the shared
  design-system **`OpenCodeBottomSheet`** with **`OpenCodeBottomSheetEntry`** rows
  (checkmark on the current selection). Circular actions use the reusable
  low-contrast **`OpenCodeButton`** component.
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
- **2026-06-27:** Timeline subscribes to `MessageLiveStore` only; pull-to-refresh,
  post-send reload, and error retry use `refreshMessages`. No duplicate REST on bind.
- **2026-06-28:** Model-selection cache load/save and initial selection resolution
  live in `SessionChatComposer` on both platforms. See
  [Keep SessionChatViewModel as a thin facade](decisions.md#keep-sessionchatviewmodel-as-a-thin-facade).
- **2026-07-06 / OPE-30:** Production `SessionChatTimelineView` uses the shared
  auto-follow state helper and pinned-bottom detector. Appended messages advance
  to the bottom only when the user has not scrolled away from the latest message.

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
- **2026-06-27:** Message observation moved from ViewModel into Timeline via
  `MessageLiveStore`; `loadMessages()` loads catalog only (matches screen `.task` flow).
- **2026-06-28:** Model-selection cache moved from `SessionChatViewModel` into
  `SessionChatComposer` (parity with Android).
- **2026-07-06 / OPE-30:** Production `SessionChatTimelineView` uses the same
  pinned-bottom auto-follow state as the Message List gallery. Appended messages
  advance to the bottom only when the user has not scrolled away from the latest
  message.

## Architecture guardrails

- Session Chat Screen Model changes are **high-risk**. Read
  [Keep SessionChatViewModel as a thin facade](decisions.md#keep-sessionchatviewmodel-as-a-thin-facade)
  before editing `SessionChatViewModel` on either platform.

## Validation Status

- Android `:app:compileDebugKotlin` passed on 2026-06-01.
- Android `:app:testDebugUnitTest` passed on 2026-06-01.
- iOS simulator debug build passed on 2026-06-01.
- iOS `OpenCode Mobile` test action passed on 2026-06-01.
- OPE-30 Component Gallery prototype: Android `:app:compileDebugKotlin`, iOS
  simulator build/run, Android emulator install/run, and manual Message List
  auto-follow verification passed on 2026-07-05.
- OPE-30 production Session Chat auto-follow promotion: Android targeted
  auto-follow state tests passed, iOS targeted auto-follow state tests passed,
  and iOS simulator build passed on 2026-07-06. Full iOS test action still has a
  benchmark fixture failure in
  `SessionChatViewModelTests.benchmark_loadMessages_longMarkdownHistory`.
- OPE-30 production sticky-bottom follow now handles layout drift while armed:
  markdown/render growth, streaming row height changes, composer/keyboard
  viewport changes, and status/notice rows can keep the bottom anchored without
  requiring a new message count.
- Follow now also treats message layout-signature changes, including
  `renderedMarkdown` becoming available after the raw row appears, as a
  post-layout sticky-bottom recheck instead of relying only on content-height
  callbacks.
- Production Session Chat keeps its visual bottom gap above the composer while
  Android scroll math now includes bottom content padding, so send-time
  auto-follow uses the same end position that the list settles to.
- Android production Session Chat now performs the same one-time initial
  scroll-to-latest as iOS when a ready timeline first receives non-empty history.
- Message List Component Gallery now has floating `+`, `-`, and `/` controls to
  append a message, remove the last message, and toggle the last message height
  for direct scroll behavior testing.
- Manual validation in local IDE runtime remains pending.
- Expected flow: tap row -> open chat -> send text -> local message appears.
- Composer selectors: chip taps open bottom sheets for model, agent mode, and
  Variant; attachment remains a no-op.

## Remaining Work

- Permission request handling remains tracked outside this status-bar slice.

## Big Session Performance Investigation

- Fixture: the manually named **Big Session** on the local opencode server. It
  contains a large syntax-highlighted code response and is used only as a local
  performance repro, not as a deterministic CI fixture.

### Current (2026-06-27, libonig + full-history enrichment)

- Manual device test: **~2.5 s** cold open to styled timeline on iOS, matching
  Android for the same session.
- iOS and Android both enrich the full merged timeline on load.
- Warm revisits remain fast because the in-memory render-document cache is keyed
  by message text.

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

### Hot path (unchanged)

`SessionChatViewModel` → `RenderedMarkdownMapper.enrichRows()` →
`OpenCodeMarkdownParser.parse()` (Android) / JSON bridge (iOS) →
`parseFence()` → `OpenCodeSyntaxHighlighter.highlight()` →
`OpenCodeTextMateNativeTokenizer.tokenize()`.

### Experiments tried

- removing the duplicate foreground history fetch while the live store reloads;
- cache-first history publication;
- moving history DTO adaptation off the main actor;
- publishing raw/unstyled rows before rich rendering (rejected: unresponsive and
  degraded styling);
- preparing only a latest-message window (rejected 2026-06-27: Android parity);
- **libonig backend** (shipped): restored acceptable Big Session cold open.

### Next optimization candidates

- virtualize or collapse large code blocks for the first frame;
- slim or bypass the iOS JSON bridge if it shows up on other profiles.
