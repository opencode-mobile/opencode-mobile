# Session Chat Progress

## Shared Planning

- Status: complete for scaffold-first Session Chat entry.
- Shared scope: row tap opens Session Chat with local message/composer behavior.

## Design System

- Home session rows reuse existing list-item patterns.
- Session Chat scaffold uses existing platform theme tokens and typography.
- Screen chrome uses **`OpenCodeTopBar`**: title **Session** on line 1, foreground
  SSE status on line 2 via `subtitleAccessory.statusDot`, leading **back**
  (pops the navigation stack), overflow menu, and optional bottom divider.
- Session status bar shows only the context-usage ring when token and
  model-limit data are available.
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
- **2026-06-27:** Timeline subscribes to `MessageLiveStore` only; pull-to-refresh,
  post-send reload, and error retry use `refreshMessages`. No duplicate REST on bind.
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
- **2026-06-27:** Message observation moved from ViewModel into Timeline via
  `MessageLiveStore`; `loadMessages()` loads catalog only (matches screen `.task` flow).
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
- **2026-07-17:** Stabilized Session Chat scrolling and streaming resize on iOS.
  Programmatic scroll tasks are serialized; content-growth and viewport-resize
  offset changes no longer count as user scrolling; markdown block identities
  stay stable across streaming growth; inline markdown styles apply
  synchronously; last-row size animation (and its per-frame follow storm) is
  removed so `defaultScrollAnchor(.bottom)` plus discrete follow snaps handle
  pin.

## Architecture guardrails

- Session Chat Screen Model changes are **high-risk**. Read
  [Keep SessionChatViewModel as a thin facade](decisions.md#keep-sessionchatviewmodel-as-a-thin-facade)
  before editing `SessionChatViewModel` on either platform.

## Behavior hardening

- Production sticky-bottom follow handles markdown/render growth, streaming row
  height changes, composer/keyboard viewport changes, and status/notice rows
  while follow is armed.
- Message layout-signature changes, including `renderedMarkdown` becoming
  available after the raw row appears, trigger a post-layout follow recheck.
- Android scroll math includes bottom content padding and performs the same
  one-time initial scroll-to-latest as iOS.
- Production Session Chat uses non-animated exact end correction for layout and
  viewport growth; append and manual scroll-to-end actions remain animated.
- **2026-07-17:** iOS no longer animates last-row size growth. Streaming and
  markdown height changes publish instantly; Android keeps `animateContentSize`
  on the newest message. See
  [Stabilize iOS timeline scroll under streaming growth](decisions.md#stabilize-ios-timeline-scroll-under-streaming-growth).
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

- No remaining implementation work in the current Session Chat slice.
- Dedicated diff viewing remains separate feature work. Real attachment handling
  is delivered under [File Attachments](../file-attachments/progress.md).

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
