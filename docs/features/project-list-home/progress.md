# Session List Home Progress

## Shared Planning

- Status: complete for the original session-first home slice and superseded by
  [Project Selection](../project-selection/progress.md) for current home
  behavior.
- Root destination remains session-oriented home on both apps.

## Design System

- Added reusable `List Item` component for Android and iOS.
- Added component gallery entries and sample states for List Item.
- Added component doc at `docs/design-system/components/list-item.md`.

## Android

- The original slice shipped a hardcoded endpoint and fixed project context.
  [Server Connection](../server-connection/progress.md) now supplies the active
  Connection Profile and [Project Selection](../project-selection/progress.md)
  supplies curated worktree selection.
- Session-list loading/empty/error states continue to load through
  `SessionRepository` and Hilt.
- "Developer Components" action from session home.

## iOS

- The original slice shipped a hardcoded endpoint and fixed project context.
  [Server Connection](../server-connection/progress.md) now supplies the active
  Connection Profile and [Project Selection](../project-selection/progress.md)
  supplies curated worktree selection.
- `ProjectListHomeView` and `ProjectListHomeViewModel` retain
  loading/loaded/empty/error behavior and load through `SessionRepository` and
  Factory.
- "Developer Components" action from session home.

## Validation Status

- Component previews for List Item on both platforms.
- ViewModel unit tests with fake `SessionRepository` on both platforms.
- Manual: `opencode serve` on `:4096` — see
  [`session-repository/manual-test.md`](../session-repository/manual-test.md).

## Remaining Work

- None in this superseded slice. Current implementation and remaining work live
  under [Project Selection](../project-selection/progress.md),
  [Server Connection](../server-connection/progress.md), and
  [Message Repository](../message-repository/progress.md).
