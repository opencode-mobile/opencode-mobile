# Foreground Event Stream — Parity Gaps

## iOS session status directory scoping

- **Status:** Fixed in this slice
- **Area:** Behavior
- **Shared intent:** `getAllSessionStatuses` respects worktree `directory` query like Android.
- **Previous iOS state:** Repository ignored `directory` even though API client supported it.
- **Current Android state:** Passes `directory` from section/chat load paths.
- **Current iOS state:** Repository and ViewModels pass `directory`.
- **Reason:** Oversight in original Session Repository slice.
- **Owner or next action:** None.
