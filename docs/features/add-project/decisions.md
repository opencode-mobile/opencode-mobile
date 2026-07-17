# Add Project — Decisions

## Filesystem browse replaces project catalog

Adding a project no longer lists `GET /project`. Users browse the server
filesystem from `GET /path` home via `GET /file?path=`, then confirm the current
directory. Catalog browse in project-selection is superseded.

## Validate before persist

Confirming the current directory calls `GET /project/current?directory=` and
persists the returned worktree. Invalid paths stay in the sheet with Retry.

## Already-added re-focus

If the path is already in the curated list, skip validation and re-focus that
project on home.

## Listing policy

Show directories only, hide names starting with `.`, sort by name, cap at 200
entries.

## Fixed 50% sheet

The Add project sheet always uses the bottom-sheet fixed half-height detent with
pinned Go up + Current dir rows above the scrollable listing.
