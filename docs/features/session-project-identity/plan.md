# Session Project Identity

## User Goal

On Session Chat, always know which project (added worktree) the open session
belongs to, without losing the session title or live connection status.

## In Scope

- Show project identity in Session Chat top-bar chrome using the locked **D2**
  layout (see [decisions.md](./decisions.md)).
- Derive a single **smart project label** from the session’s worktree and the
  user’s other added projects for the active Connection Profile:
  - unique basename among added projects → basename only (e.g. `opencode`);
  - name collision → shortest distinguishing parent prefix folded into one
    string (e.g. `..Projects/opencode`, `..Documents/opencode`);
  - if immediate parents also match, walk up until the suffix is unique
    (e.g. `..acme/lib/opencode`).
- Keep **session title** as the primary top-bar title (live-updated as today).
- Keep **connection status** visible on the second line (`·` + status dot +
  lowercase label), co-located with the project label.
- Show the project **letter badge** (same letter/accent rules as home) leading
  the project label on the second line.
- Pass or derive display inputs already known at navigation time (`worktree`)
  plus added-project peers needed for disambiguation (from local profile
  storage — no new server API).

## Out Of Scope

- Full worktree path as a second field or always-on subtitle.
- Replacing session title with project name (project-first title variants).
- Project identity on the composer, a separate context strip, or overflow-only.
- Changing home project rows or Session List path subtitles (they keep their
  own compaction rules unless a later slice unifies helpers).
- Multi-server / multi-profile project identity beyond the active profile’s
  added worktrees.
- Tappable project label navigation (v1 is read-only chrome).

## Server APIs Involved

None new. Session Chat remains scoped with existing `directory={worktree}`.
Disambiguation uses local added-project worktrees for the active server URL.

## Domain Objects

- Session navigation inputs: `sessionId`, `sessionTitle`, `worktree`.
- Added project peers: worktree list for the active Connection Profile server
  URL (existing local store).
- Smart project label: pure function of `(focusWorktree, peerWorktrees)` →
  display string + optional letter/accent from existing home helpers.
- Connection status: existing SSE foreground status already shown on Session
  Chat.

## Screen Model

- Session Chat chrome gains project label fields on the region that owns top-bar
  inputs (today: facade / `SessionInfo` + nav worktree), not a new screen.
- UI state (conceptual): `sessionTitle`, `projectLetter`, `projectLetterAccent`,
  `projectLabel`, `connectionStatus`.
- No new user actions for v1 (label is not interactive).

## Native Navigation

- Existing Session Chat entry from home / session list; worktree already on the
  route. May need peer worktrees from profile store inside the screen model
  rather than bloating nav args.

## Error States

- Missing worktree: omit project letter/label; keep title + connection status
  (should not happen on normal home entry).
- Empty added-project list: treat focus worktree as unique → basename only.

## Design-System Components

| Piece | Status |
| --- | --- |
| `OpenCodeTopBar` title = session title | Existing |
| Second line: letter + smart label + connection status | **Extend** top bar (or documented composed subtitle accessory) so status and project text coexist — today plain `subtitle` is ignored when `statusDot` is set |
| Letter badge / accent | Reuse home letter helpers; may be feature-composed if top bar has no letter slot |
| `OpenCodeConnectionStatusLabel` | Existing; keep lowercase live/connecting/offline semantics |
| Smart label helper | Shared pure helper (Android + iOS parity), not a visual component |

Prototype source (throwaway): `docs/prototypes/session-project-info-d3/` and
earlier D-series under `docs/prototypes/session-project-info*/`.
