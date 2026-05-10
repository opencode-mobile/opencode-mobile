# Session Chat Progress

## Shared Planning

- Status: complete for scaffold-first Session Chat entry.
- Shared scope: row tap opens Session Chat with local message/composer behavior.

## Design System

- Reused existing list-item component patterns for home session rows.
- Session Chat scaffold uses existing platform theme tokens and typography.
- No new reusable design-system component introduced in this slice.
- Composer selectors use the reusable `OpenCodeChoiceBadge` component; circular
  action buttons use the reusable low-contrast `OpenCodeButton` component.

## Android

- Added session-chat navigation key and destination.
- Wired session row tap from `MainScreen` to Session Chat route.
- Added Session Chat scaffold with local message list and composer send behavior.
- Refined composer into prompt input, local selector chips, attachment
  affordance, and Send action rows.

## iOS

- Added Session Chat destination view.
- Wired `ProjectListHomeView` session rows with `Button` plus
  `navigationDestination(item:)` so **`List` does not show disclosure chevrons**
  (SwiftUI adds chevrons for `NavigationLink` rows by default).
- Added local message list and composer send behavior in Session Chat.
- Refined composer to match Android's prompt input, local selector chips,
  attachment affordance, and Send action rows.

## Validation Status

- Android `:app:compileDebugKotlin` passed on 2026-05-09.
- iOS simulator debug build passed on 2026-05-09.
- Manual validation in local IDE runtime remains pending.
- Expected flow: tap row -> open chat -> send text -> local message appears.
- Composer selector validation pending: chip taps cycle local mode and Thinking
  Effort state; attachment remains a no-op.

## Remaining Work

- Replace local messages with server-backed history.
- Connect composer send to real prompt API.
- Add stream updates, abort behavior, and permission request handling.
