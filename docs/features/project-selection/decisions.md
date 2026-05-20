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

When no projects are added, show hint **Add a project to see recent sessions.**
below the browse row. Each added project section shows up to three recent
sessions; overflow uses **See all...** in a bottom sheet titled with the project
display name. A project with no sessions offers **New Session** instead of previews.

## Worktree-stable letter accent

Project list rows use the first letter of the display name and a letter accent
color derived deterministically from the worktree path so the same project keeps
the same accent across launches.
