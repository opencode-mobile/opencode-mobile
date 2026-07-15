# opencode-mobile Context

`opencode-mobile` is a mobile client project for remotely controlling opencode
sessions from native iOS and Android apps.

The project documents product scope, feasibility, and native app architecture.
App shells exist under `android/` and `ios/OpenCode Mobile/`. Session list home
loads curated **added projects** and sessions via **Project Selection**;
**Session Repository** and **Message Repository** scope calls with `directory`;
Session Chat sends async prompts (see `docs/features/project-selection/`,
`docs/features/session-repository/`, `docs/features/message-repository/`).
OpenAPI fetch tooling and maintained sessions/messages specs exist; full `/doc`
codegen is not wired into app builds yet.
Release processes are not started.

## Bounded Context

The mobile client connects to an opencode server that is already running for a
development project. In v1, the mobile client operates inside that server's
current project context and does not switch projects remotely.

```mermaid
flowchart LR
  iosApp[iOS App] -->|"HTTP plus SSE"| opencodeServer[opencode server]
  androidApp[Android App] -->|"HTTP plus SSE"| opencodeServer
  opencodeServer --> currentProject[Current Project]
  opencodeServer --> sessions[Sessions]
```

## Glossary

### opencode server

The HTTP server exposed by opencode. It provides the API used by the mobile
client to inspect and control sessions.

For v1, the server is expected to run on the user's development machine. The
user may reach it through LAN, VPN, HTTPS, or a tunnel, but the mobile client
does not provide that transport.

### Connection Profile

A saved mobile configuration for one reachable opencode server.

A Connection Profile includes the server base URL, username, password (stored in
platform secure storage), and an ordered list of **added project** worktree
paths the user chose to show on home. It represents how the mobile client reaches
opencode, not a user account. v1 supports one active Connection Profile at a
time.

### Connection onboarding

The first-run screen shown when no Connection Profile is saved.

Connection onboarding explains that the app needs an opencode server and offers
a primary action to open the connection setup flow. It is not shown when a
profile exists but the server is temporarily unreachable.

### Added project

A development project worktree the user explicitly added to the home list for a
Connection Profile.

Adding a project is local curation only; it does not create a project on the
server. The app scopes API calls with that worktree path.

### current project

The project context the opencode server uses for a request.

When the mobile client sends `directory={worktree}` on API calls, **current
project** means that worktree. With no `directory` parameter, it means the
server process boot worktree (`GET /project/current` without a query).

### session

A conversation and work stream managed by opencode for the current project.

The mobile client can list sessions, create sessions, rename sessions, delete
sessions, fork sessions, inspect their messages, send prompts, abort active
work, review diffs, and respond to permission requests.

### Session Repository

The mobile integration layer that performs session lifecycle operations against
the opencode server (list, create, update, delete, fork, status, abort, diff,
permissions, and related session-scoped controls).

Distinct from a **session**: the repository is app infrastructure; the session
is the server-managed conversation for the current project.

### Message Repository

The mobile integration layer that reads and sends **messages** for a session
(list, get one, sync send, and asynchronous prompt send).

Distinct from a **message**: the repository is app infrastructure; a message is
a single item in a session timeline.

### Live Store

A reactive observation layer above CRUD repositories that keeps screen data fresh
while the **foreground event stream** is active.

A Live Store listens for relevant server-sent events, applies debounced REST
refresh policy, and exposes ongoing updates through platform streams (for
example Kotlin `Flow` or Swift `AsyncStream`). Screen Models collect Live Store
streams for timelines, session lists, and status labels. Mutations such as
send, create, and delete still go through repositories directly. Optimistic send
and quiet refresh merge stay in the Screen Model.

### message

A user, assistant, or tool-related item inside a session.

Each message has a `role` (user/assistant), an ordered array of `parts`, and a
`text` projection of TextPart content for display. Tool calls are not flattened
into `text` when Session Chat shows them as Tool Call Cards. A message with no
meaningful parts (empty prompt, system-only) still has a `text` fallback.

### message part

A typed content unit within a message. Parts are ordered. For v1 the domain
model defines two variants:

- **TextPart**: user prompt text or assistant response text. May carry
  `isStreaming: true` while delta chunks are still arriving. Model reasoning
  traces are hidden from normal chat text in v1.
- **ToolPart**: a named tool call inside a message, with a tool status,
  optional running title, optional error text, call id when the server
  provides one, opaque string-keyed input and metadata maps, and optional
  string output for later card chrome.
  _Avoid_: treating a preformatted status string as the tool part itself;
  modeling every server tool schema as its own domain type in v1.

### timeline block

A Session Chat presentation unit inside one message timeline item: a text
block (Rendered Markdown from adjacent TextParts), a tool card block (Tool
Call Card from a visible ToolPart), or a **tool group** block.
_Avoid_: treating each block as its own top-level message; flattening tool
labels into message `text` for Session Chat display.

### tool group

A Session Chat timeline block that summarizes consecutive context ToolParts
(`read` / `glob` / `grep`) inside one message as a single Tool Call Card.
A text block or a different tool family breaks the group.
_Avoid_: expanding each grouped tool inline; treating a group as multiple
timeline messages.

### child session

A session spawned from a parent session to carry out delegated work.

The parent timeline surfaces that work as a `task` ToolPart. Opening the card
navigates into the child session when a session id is available.
_Avoid_: subagent (informal/desktop slang for the same child session).

### tool status

Where a ToolPart is in its lifecycle: `pending`, `running`, `completed`, or
`error`. Missing or unrecognized server status is treated as `pending`.

Session Chat shows status on the Tool Call Card. Compact emoji labels (for
example ⌛ / ⏳ / ✓ / ✕) are a legacy display formatter for non-card surfaces,
not stored on ToolPart.
_Avoid_: encoding status only as emoji or a display string.

Server-only part types (`step-start`, `step-finish`, `agent`, `subtask`,
`snapshot`, `patch`, `compaction`, `retry`) are consumed by the adapter but do
not produce a visible domain part in v1.

### Rendered Markdown

Formatted display of message text that may include headings, lists, links,
tables, inline code, fenced code blocks, and fenced diff blocks.

Rendered Markdown is a display projection only. The source text remains raw
markdown for sending, copying, restoring to the composer, and repository/domain
state.

### Model Selection

The user's selected provider/model pair and optional server-provided Variant
for the next prompt.

The mobile client loads Model Selection options from the opencode server for the
current project. `Default` Variant is a mobile UI label meaning no explicit
variant is sent.

When a Model Selection already exists, changing it starts from models offered by
the selected Provider, with a way back to the Provider list.

### New Session Model Preference

The Model Selection explicitly chosen on this device as the starting choice for
new sessions in the same Connection Profile and Added Project.

It is not inferred from session history, merely opening a session, or activity
from another client.

### Thinking Effort

Legacy planning term for a reasoning-effort preference. In Session Chat, the
server-backed user-facing selector is **Variant**.

### permission request

A request from opencode for the user to approve or deny an action during a
session.

The mobile client may approve or deny a permission request in v1, but it should
not create persistent `remember` decisions from mobile.

### diff

The line-level representation of one file's changes inside a **Change Set**.

The mobile client may show diffs so the user can understand the result of remote
work. In v1, diff viewing does not imply mobile file editing.

### Change Set

A collection of file diffs selected for review from one of three sources:

- **Last turn**: changes attributed to the latest user turn in the session.
- **Working tree**: current uncommitted changes in the project's Git working
  tree.
- **Main branch**: current branch changes relative to its default branch.

**Main branch** is available only when the server reports that the project uses
Git and the current branch differs from the default branch. A Change Set is a
read-only review projection; it does not imply mobile file editing or patch
application.

### Session Context

A session-scoped inspection view for the model context and message accounting
behind a **session**.

Session Context shows provider and model labels, context limit, token usage,
message counts, cost, approximate input-token breakdown, and raw message rows.
It is opened from the context usage control in Session Chat. It is a snapshot
that can be manually refreshed, not a live-updating timeline.

### foreground event stream

The live server-sent event connection used while the mobile app is open and
active.

The app uses this stream to update session state in the foreground. When the app
reconnects, it refreshes state instead of relying on guaranteed background
delivery.

### OC-2 theme

A desktop-compatible theme file format (JSON) used by OpenCode clients.

Each theme defines `light` and `dark` variants with palette seeds and optional
token overrides. Mobile apps resolve seeds into semantic color tokens at
runtime. Users pick bundled desktop presets or import custom OC-2 JSON in
Settings.

### Design System

The shared visual and interaction language for both native apps.

The Design System defines semantic tokens, typography roles, reusable
components, component states, and intentional platform differences. SwiftUI and
Android Material 3 may expose different platform APIs, but they should preserve
the same product meaning.

### Component Collection

The set of reusable Design System components available to feature screens.

The Component Collection is built and kept in sync across iOS and Android before
feature screens depend on reusable UI. Each component should have documented
intent, supported variants, states, and platform differences.

### Component Gallery

A developer-only in-app inspection surface for the Component Collection and
Design System foundations.

The Component Gallery is allowed to be the temporary app entry point during
design-system work. It lists gallery entries and opens a dedicated screen per
entry with consistent controls for reviewing themes, states, variants, and
platform behavior. It is not part of the v1 user-facing product navigation.

### Feature Slice

A user-facing capability developed across both native apps as one coordinated
unit of work.

A Feature Slice includes shared product intent, platform implementations,
feature documentation, and any Parity Gaps that remain while the apps are being
brought into sync.

### Screen Model

The feature-level object that prepares state and handles user actions for a
screen.

A Screen Model exposes an immutable view of the screen's state and explicit
actions or events. The concept is shared across platforms even though each app
uses its native implementation tools.

### Parity Gap

A documented difference between the iOS and Android apps.

A Parity Gap may describe behavior, state, copy, visual semantics, token
mapping, accessibility behavior, error handling, or missing implementation. It
records the shared intent, current platform states, reason, next action, and
whether the difference is intentional or temporary.

## Deferred Concepts

These concepts are intentionally outside the v1 context:

- mobile IDE;
- mobile terminal remote;
- app-managed tunnel or relay;
- account system;
- push notification service;
- multi-project manager;
- direct remote admin console for shell, config, auth, or MCP management.
