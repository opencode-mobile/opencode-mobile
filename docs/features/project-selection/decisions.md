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

## First-launch seed

Auto-add `GET /project/current` worktree when added list is empty after successful probe.

## Widgets icon for Component Gallery entry

Home top-bar trailing action opens the developer-only Component Gallery. Use the
catalog `widgets` glyph (`OpenCodeIcons.Widgets` / `OpenCodeIcon.widgets`) with
accessibility label **Developer components**, not `edit`. The pencil icon remains
for gallery samples and other edit affordances.

## Browse projects affordance

Curated add flow uses list row and bottom sheet title **Browse projects** (add
icon start decorator). Sheet lists server projects not already in the added set;
empty catalog shows **No more projects on this server**.

## Home empty and session previews

The original home list showed **Browse projects**, an empty hint, and repeated
project sections with up to three recent sessions. The **Project Session
Selector** replaces that list on home.

## Worktree-stable letter accent

Project list rows use the first letter of the display name and a letter accent
color derived deterministically from the worktree path so the same project keeps
the same accent across launches.

## Project Session Selector

Home uses the shared Component Collection **Project Session Selector** as the
primary project and session selector. The legacy **Browse projects** row and
repeated project/session sections are removed from home; browsing remains
available from the selector add tile.

Project selection is local home UI state only. It chooses which added project's
sessions appear in the selector card and which worktree the selector **New
session** action uses. It does not switch the server's current project, mutate
the Connection Profile beyond adding projects, or persist across app launches.

Fresh home loads focus the first added project by insertion order. Adding a
project through **Browse projects** focuses the newly added worktree. The
selector is available in the Component Gallery with normal, empty, load-error,
create-error, and selected-project scenarios for isolated iteration.
