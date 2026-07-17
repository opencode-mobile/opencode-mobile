# Add Project Directory Navigator

Linear: [OPE-63](https://linear.app/opencode-mobile/issue/OPE-63/add-project-directory-navigator)

## User Goal

Add a project by browsing the opencode server filesystem from home, navigating
directories, and confirming the current path so it is validated, cached, and
focused on home.

## In Scope

- Replace catalog **Browse projects** with an **Add project** bottom sheet.
- Fixed 50% sheet height with pinned **Go up** and **Current dir** rows.
- `GET /path` for home start; `GET /file?path=.&directory=` for directory listing.
- Directory-only rows; hide `.*` names; truncate long listings.
- Tap current directory validates via `GET /project/current?directory=`,
  persists the selected directory (not response worktree), focuses, dismisses.
- Loading / error + Retry; already-added re-focus without re-validation.
- iOS and Android parity.

## Out Of Scope

- Remove project
- Manual path entry
- Fuzzy search
- Full file browsing / mobile IDE

## Server APIs

- `GET /path`
- `GET /file?path=.&directory=`
- `GET /project/current?directory=`

## Design System

- `OpenCodeBottomSheet` `alwaysFixedHeight` for always-50% presentation.
- Pinned content for Go up + Current dir.
- Folder Check / Subdirectory Arrow Right / arrow-upward catalog icons on rows.
