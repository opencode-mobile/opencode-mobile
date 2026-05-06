# Session List Home Progress

## Shared Planning

- Status: complete for initial hardcoded-server slice with session-first
  semantics.
- Root destination changed to session list home on both apps.

## Design System

- Added reusable `List Item` component for Android and iOS.
- Added component gallery entries and sample states for List Item.
- Added component doc at `docs/design-system/components/list-item.md`.

## Android

- Added hardcoded endpoint constant (`AppConfig.SERVER_BASE_URL`).
- Updated home screen to session-list layout with loading/empty/error states.
- Added fixed selected project context (`opencode-mobile`).
- Added "Developer Components" action from session home.
- Navigation now starts at `ProjectListHome` and routes to component gallery.

## iOS

- Added hardcoded endpoint constant (`AppConfig.serverBaseURL`).
- Added `ProjectListHomeView` with loading/loaded/empty/error state rendering.
- Added fixed selected project context (`opencode-mobile`).
- Added bottom "Developer Components" action from session home.
- `ContentView` now starts from session home flow.

## Validation Status

- Component previews added for List Item on both platforms.
- Additional build checks should be re-run when local platform toolchains are
  available in CI/IDE.

## Remaining Work

- Replace mocked session rows with real server-backed sessions for selected
  project.
- Implement project selection/switch flow and remove fixed selected-project
  placeholder.
- Introduce connection setup/profile flow and remove hardcoded endpoint.
