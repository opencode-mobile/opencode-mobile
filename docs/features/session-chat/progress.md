# Session Chat Progress

## Shared Planning

- Status: complete for scaffold-first Session Chat entry.
- Shared scope: row tap opens Session Chat with local message/composer behavior.

## Design System

- Home session rows reuse existing list-item patterns.
- Session Chat scaffold uses existing platform theme tokens and typography.
- Screen chrome uses **`OpenCodeTopBar`**: title **Session**, leading **back**
  (pops the navigation stack), optional bottom divider; session identity stays in
  a 24 dp/pt status bar under the top bar (not in the top-bar title).
- Session status bar shows copyable session id, foreground SSE state, and a
  context-usage ring when token and model-limit data are available.
- Session status bar is promoted to the shared Component Collection as
  **`OpenCodeSessionStatusBar`**.
- Composer chips use `OpenCodeChoiceBadge`; tapping a chip opens the shared
  design-system **`OpenCodeBottomSheet`** with **`OpenCodeBottomSheetEntry`** rows
  (checkmark on the current selection). Circular actions use the reusable
  low-contrast **`OpenCodeButton`** component.

## Android

- Added session-chat navigation key and destination.
- Wired session row tap from `MainScreen` to Session Chat route.
- Added Session Chat scaffold with local message list and composer send behavior.
- Replaced the standalone Session ID row with the 24 dp status bar.
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
- Replaced the standalone Session ID row with the 24 pt status bar.
- Added Session Chat Screen Model state for foreground event-stream connection
  status and context usage.
- Refined composer to match Android's prompt input, local selector chips,
  attachment affordance, and Send action rows.
- Composer selector chips open **`openCodeBottomSheet`** pickers instead of
  cycling values in place.

## Validation Status

- Android `:app:compileDebugKotlin` passed on 2026-05-10.
- iOS simulator debug build passed on 2026-05-10.
- Manual validation in local IDE runtime remains pending.
- Expected flow: tap row -> open chat -> send text -> local message appears.
- Composer selectors: chip taps open bottom sheets for model, agent mode, and
  Variant; attachment remains a no-op.

## Remaining Work

- Server-backed history and async send: see [Message Repository](../message-repository/progress.md).
- Add stream updates and permission request handling — stream updates and abort behavior shipped via [Foreground Event Stream](../foreground-event-stream/progress.md).
