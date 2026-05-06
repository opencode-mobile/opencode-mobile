# Session List Home

## User Goal

Open the app directly into a session list for a selected project so users can
quickly continue active work from their opencode server context instead of
landing in developer-only component gallery screens.

## In Scope

- Replace default home destination with Session List on iOS and Android.
- Hardcode server base URL to `http://127.0.0.1:4096/` for this slice.
- Show fixed selected project context (`opencode-mobile`) until project
  switching is implemented.
- Render mocked session rows using a reusable list-item component.
- Keep component gallery reachable from a secondary "Developer Components"
  affordance.

## Out Of Scope

- Project selection and project switching.
- Authentication and secure credential storage.
- Real `/app` API integration and persistence/caching behavior.

## Server APIs Involved

- None yet. Session rows are mocked while the hardcoded endpoint and selected
  project context are displayed.

## Domain Objects

- `SessionSummary` (id + title) as temporary Android local projection.
- `SessionListHomeState` as temporary iOS home-state model.

## UI States

- Loading.
- Loaded with sessions.
- Empty list.
- Error with fallback copy.

## Error States

- Generic session-load error fallback text.

## Permission Behavior

- Not applicable in this slice.

## Design-System Components Needed

- New reusable `List Item` component for session rows (and future project rows).
