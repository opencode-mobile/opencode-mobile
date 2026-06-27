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

## Android

- Added session-chat navigation key and destination.
- Wired session row tap from `MainScreen` to Session Chat route.
- Added Session Chat scaffold with local message list and composer send behavior.
- Replaced the standalone Session ID row with the 36 dp status bar.
- Added Session Chat Screen Model state for foreground event-stream connection
  status and context usage.
- Refined composer into prompt input, local selector chips, attachment
  affordance, and Send action rows.
- Composer selector chips open **`OpenCodeBottomSheet`** pickers instead of
  cycling values in place.

## iOS

- Added Session Chat destination view.
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

## Validation Status

- Android `:app:compileDebugKotlin` passed on 2026-06-01.
- Android `:app:testDebugUnitTest` passed on 2026-06-01.
- iOS simulator debug build passed on 2026-06-01.
- iOS `OpenCode Mobile` test action passed on 2026-06-01.
- Manual validation in local IDE runtime remains pending.
- Expected flow: tap row -> open chat -> send text -> local message appears.
- Composer selectors: chip taps open bottom sheets for model, agent mode, and
  Variant; attachment remains a no-op.

## Remaining Work

- Permission request handling remains tracked outside this status-bar slice.

## Big Session Performance Investigation

- Date: 2026-06-24.
- Fixture: the manually named **Big Session** on the local opencode server. It
  contains a large syntax-highlighted code response and is used only as a local
  performance repro, not as a deterministic CI fixture.
- Measurement: an opt-in iOS UI benchmark emits Darwin-notification probes from
  the app, avoiding XCTest accessibility polling while rich text is rendering.
- Latest measured cold-path breakdown:

  | Stage | Elapsed from benchmark start |
  | --- | ---: |
  | Session screen load started | 2.940 s |
  | Full history request started | 2.944 s |
  | Full history request finished | 3.061 s |
  | Markdown preparation started | 3.082 s |
  | Markdown preparation finished / styled timeline published | 27.303 s |

- Finding: the full-history request took **0.118 s**; Markdown preparation for
  the initial visible window took **24.221 s**. The cold-path bottleneck is
  KMP markdown document preparation in `shared/markdown-core`, not server
  history retrieval or SwiftUI layout.
- Hot path (all in `shared/markdown-core`):
  `SessionChatViewModel` → `RenderedMarkdownMapper.enrichRows()` →
  `OpenCodeMarkdownParser.parse()` → `parseFence()` →
  `OpenCodeSyntaxHighlighter.highlight()` →
  `OpenCodeTextMateNativeTokenizer.tokenize()`. Block structure parsing,
  inline parsing, and `OpenCodeMarkdownDetector.shouldRender()` are cheap
  (`detector-ms=0` on the deterministic 20-message / 3,000-line fixture).
- iOS and Android both enrich the full merged timeline on load. A single large
  fenced code block inside the timeline can still dominate cold open on both
  platforms.
- Warm revisits are fast because the in-memory render-document cache is keyed
  by message text. Cold opening remains the active problem.
- Experiments tried:
  - removing the duplicate foreground history fetch while the live store reloads;
  - cache-first history publication;
  - moving history DTO adaptation off the main actor;
  - publishing raw/unstyled rows before rich rendering (rejected: the app was
    unresponsive while documents were prepared and the result degraded styling);
  - preparing only a latest-message window (still blocked by a large code
    message within that window).
- Next experiment: retain syntax styling but virtualize/collapse large code
  blocks for the first frame, expanding the complete block only on demand.
