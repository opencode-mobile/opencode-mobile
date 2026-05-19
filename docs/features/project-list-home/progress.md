# Session List Home Progress

## Shared Planning

- Status: complete for session-first home with real `GET /session` list.
- Root destination is session list home on both apps.

## Design System

- Added reusable `List Item` component for Android and iOS.
- Added component gallery entries and sample states for List Item.
- Added component doc at `docs/design-system/components/list-item.md`.

## Android

- Hardcoded endpoint constant (`AppConfig.SERVER_BASE_URL`); emulators rewrite
  to `10.0.2.2` (see [`session-repository`](../session-repository/manual-test.md)).
- Session-list layout with loading/empty/error states; loads via
  `SessionRepository.listSessions()` and Hilt.
- Fixed selected project context (`opencode-mobile`).
- "Developer Components" action from session home.

## iOS

- Hardcoded endpoint (`AppConfig.serverBaseURL`).
- `ProjectListHomeView` + `ProjectListHomeViewModel` with loading/loaded/empty/error.
- Loads via `SessionRepository.listSessions()` and Factory.
- Fixed selected project context (`opencode-mobile`).
- "Developer Components" action from session home.

## Validation Status

- Component previews for List Item on both platforms.
- ViewModel unit tests with fake `SessionRepository` on both platforms.
- Manual: `opencode serve` on `:4096` — see
  [`session-repository/manual-test.md`](../session-repository/manual-test.md).

## Remaining Work

- Implement project selection/switch flow and remove fixed selected-project
  placeholder.
- Introduce Connection Profile flow and remove hardcoded endpoint.
- Session chat real messages (`MessageRepository` slice).
