# Project Session Selector

## Intent

Provide the reusable home-scale control for choosing an added project, previewing
its recent sessions, opening a session, browsing more projects, showing all
sessions, and starting a new session in the selected project.

Use this component when a screen needs project-scoped session selection without
owning the visual treatment for project tiles or session preview rows.

## Public API

- Models:
  - `ProjectSessionSelectorProject`: `worktree`, `displayName`,
    `pathSubtitle`, `letter`, `letterAccent`, `previewSessions`,
    `totalSessionCount`, `loadError`.
  - `ProjectSessionSelectorSession`: `id`, `title`, `subtitle`.
- State:
  - selected project by `selectedWorktree`.
  - optional create-session error.
  - optional per-project load error.
- Required callbacks:
  - `onProjectSelected(worktree)`.
  - `onBrowseProjects()` (Add project tile; opens directory navigator).
  - `onSessionSelected(project, session)`.
  - `onShowAllSessions(worktree)`.
  - `onNewSession(worktree)`.

## Token Usage

- Semantic tokens: card fill uses `surface.raised` (not `surface.base` —
  `surface.base` matches `background.base` in the theme and collapses contrast);
  border subtle (`border.weaker` / outline variant); text primary / secondary;
  status error for load and create failures.
- Component dependencies: `OpenCodeButton`, `OpenCodeListItem`, catalog icons,
  and deterministic `LetterAccent` badge colors.
- Typography roles: heading for project names, code/body-secondary for paths and
  errors, list-item typography for session previews.

## Behavior

The component is stateless. Callers own selected-project state and map feature
state into component-owned project/session models.

Project tiles scroll horizontally. Selecting a tile updates selection through
`onProjectSelected`. The trailing add tile calls `onBrowseProjects`.

When a project is selected, the component shows the project title, compact path,
up to the provided preview sessions, and footer actions. Session rows call
`onSessionSelected` with both the project and session so feature screens can
convert the selection into their navigation target.

If `loadError` is present for the selected project, the session preview area
shows the error instead of session rows. If `createError` is present, the footer
shows it below the actions.

## Platform Notes

- iOS: `ProjectSessionSelector` lives under `Components` and uses SwiftUI
  buttons, `OpenCodeListItem`, and `OpenCodeButton`.
- Android: `ProjectSessionSelector` lives under `ui/components` and uses Compose
  `Surface`, `OpenCodeListItem`, and `OpenCodeButton`.

## Previews

- Light: included in platform previews and Component Gallery.
- Dark: included in platform previews through theme controls.
- Default: normal projects with recent sessions.
- Empty: project with no sessions.
- Error: project load error and create-session error.
- Selected: gallery controls allow selecting either sample project.

## Parity And Divergence

iOS and Android expose the same conceptual models and callbacks. Platform type
names differ only where native language conventions require it, such as Swift
closures and Kotlin function types.
