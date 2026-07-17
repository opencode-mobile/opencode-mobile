# Add Project — Decisions

## Filesystem browse replaces project catalog

Adding a project no longer lists `GET /project`. Users browse the server
filesystem from `GET /path` home via `GET /file?path=.&directory=`, then confirm
the current directory. Catalog browse in project-selection is superseded.

## Scope `/file` with `directory=`

`GET /file` resolves `path` under the server instance directory
(`path.join(Instance.directory, path)`). Absolute paths outside the boot
directory escape that scope and return `500 UnknownError`. The navigator always
lists with `path=.` and `directory=<absolute path being browsed>` so home and
parents of the boot worktree are reachable.

## Validate before persist

Confirming the current directory calls `GET /project/current?directory=` to ensure
the path is usable, then persists the **selected directory** (same as web
`projects.open(directory)`). Do not persist the response `worktree`: for non-git
directories the server returns the global project with `worktree` `/`, which is
not the path the user chose. Home section titles use the curated path.

## Already-added re-focus

If the path is already in the curated list, skip validation and re-focus that
project on home.

## Listing policy

Show directories only, hide names starting with `.`, sort by name, cap at 200
entries.

## Fixed 50% sheet

The Add project sheet always uses the bottom-sheet fixed half-height detent with
pinned Go up + Current dir rows above the scrollable listing.
