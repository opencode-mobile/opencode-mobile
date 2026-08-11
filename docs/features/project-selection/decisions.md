# Project Selection — Decisions

## Curated added projects

Home shows only worktrees the user added, not the full `GET /project` catalog.

## Persist worktree path

`addedProjectWorktrees` keyed by server base URL in DataStore (Android) / UserDefaults (iOS).

## Home project section order

Sections follow **insertion order** of added worktrees for that server (first add appears
first). Parallel section loads must reassemble in that order; persistence must dedupe
without shuffling (iOS initially used `Set` and task-group completion order, which
reordered sections on refresh).

## HTTP scoping

Query parameter `directory` on scoped Session/Message calls (undocumented server behavior).

## Inline health probe

Ship minimal probe in this slice; standalone sync-probe docs note overlap.

## No first-launch project seed

Do **not** auto-add the server boot worktree when the curated list is empty.
After connect/probe, home stays empty until the user adds a project via the
filesystem **Add project** flow.

## Widgets icon for Component Gallery entry

Home top-bar trailing action opens the developer-only Component Gallery. Use the
catalog `widgets` glyph (`OpenCodeIcons.Widgets` / `OpenCodeIcon.widgets`) with
accessibility label **Developer components**, not `edit`. The pencil icon remains
for gallery samples and other edit affordances.

## Browse projects affordance

> **Superseded** by [Add Project Directory Navigator](../add-project/decisions.md):
> home add tile opens a filesystem **Add project** navigator instead of the
> `GET /project` catalog sheet.

## Home empty and session previews

The original home list showed **Browse projects**, an empty hint, and repeated
project sections with up to three recent sessions. The **Project Session
Selector** replaces that list on home.

## Worktree-stable letter accent

Project list rows use the first letter of the display name and a letter accent
color derived deterministically from the worktree path so the same project keeps
the same accent across launches.

## Project Session Selector (superseded)

Home previously used **Project Session Selector** for project-scoped session
preview. That mode was retired in
[`retire-project-session-selector`](../retire-project-session-selector/decisions.md)
(OPE-139). Home now always uses Session List + dock.

## Session List (sole home mode)

Home shows a shared **Session List**: sessions from all added projects sorted by
recency. Rows use project letter, session title, project path subtitle,
always-visible **Idle** / **Running** / **Ready** status, and a relative-time
caption from `session.updatedAt`. Idle/Running/Ready copy is Session List–local
and does not replace Working/Retrying labels used in Session Chat.

Add project and New session live on the Session List dock (project picker for
new session). Project filter chips scope the list; remove-project uses chip ×.

