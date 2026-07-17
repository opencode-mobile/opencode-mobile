# Session List

## Intent

Provide the reusable home-scale list of sessions across added projects, sorted by
recency, for opening a session quickly.

Use this component when a screen needs a cross-project session feed without
owning the visual treatment for session rows.

## Public API

- Models:
  - `SessionListSession`: `id`, `title`, `worktree`, `letter`, `letterAccent`,
    `subtitle`, `status`, `caption`.
  - `SessionListStatus`: `idle` | `running`.
- Required content: ordered `sessions` collection.
- Required callbacks:
  - `onSessionSelected(session)`.
- States: default and empty. Pressed, focused, disabled, loading, error, and
  selected states are not applicable because rows and owning features expose
  those states. An empty `sessions` collection renders nothing.

## Token Usage

- Semantic tokens: list-item surface, text, status (`info`, `interactive`), and
  letter-accent pairs via `OpenCodeListItem`.
- Component dependencies: `OpenCodeListItem` with letter start decorator, status
  footer, and caption.
- Typography roles: list-item typography (`type.heading` title, `type.caption`
  subtitle/status/caption).

## Behavior

The component is stateless. Callers own sorting, relative-time caption
formatting, and Idle/Running mapping, then pass display-ready
`SessionListSession` models.

Each row maps to `OpenCodeListItem` with:

- start decorator: project letter + accent
- title: session name
- subtitle: project root directory
- status: `Idle` with tone `info`, or `Running` with tone `interactive`
- caption: relative time string
- style: `plain`

Selecting a row calls `onSessionSelected` with the full session model so feature
screens can navigate with the correct worktree.

## Platform Notes

- iOS: `SessionList` lives under `Components` and uses SwiftUI `VStack` with
  `OpenCodeListItem`.
- Android: `SessionList` lives under `ui/components` and uses Compose `Column`
  with `OpenCodeListItem`.

## Review Surfaces

Both Component Galleries include a realistic mixed Idle/Running sample with
multiple project letters and relative-time captions. Light and dark appearances
use the shared gallery theme control.

## Parity And Divergence

iOS and Android expose the same conceptual models and callbacks. Platform type
names differ only where native language conventions require it, such as Swift
closures and Kotlin function types.

## Completion Ledger

- Public API and states: complete.
- Token and typography mapping: complete; row-owned via list item.
- iOS and Android implementations: complete.
- Gallery and previews: complete.
- Parity and divergences: complete; none known.
- Automated checks: covered by platform build/test for affected surfaces.
