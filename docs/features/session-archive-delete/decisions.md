# Session Archive and Delete — Decisions

## First-class archive vs delete

- Decision: Archive and delete are distinct domain actions and glossary terms.
- Reason: Archive keeps server data and removes the session from active lists;
  delete permanently removes the session (including children). Overloading
  update/delete language would blur product meaning.

## Session Chat overflow only

- Decision: Archive and delete are available only from Session Chat overflow in
  this slice.
- Reason: Matches the grilled session-screen scope; home list actions stay out.

## Wait for server, then dismiss home

- Decision: Do not optimistically dismiss; wait for archive/delete success, then
  leave Session Chat for home. On failure stay and show an error.
- Reason: Safer on flaky mobile networks than desktop’s optimistic eviction.

## Explicit `archiveSession` repository action

- Decision: Expose `archiveSession(id)` (with directory binding) rather than
  stuffing archive into title `updateSession`.
- Reason: Keeps product language clear; rename stays a separate title update.

## Client-side archived list filter

- Decision: Exclude any session with non-null `archivedAtEpochMs` from home and
  live active lists, even if `GET /session` still returns them.
- Reason: Matches desktop web filtering; server list may include archived rows.

## Destructive Delete in overflow

- Decision: Extend overflow menu entries with optional destructive emphasis for
  Delete (last item). Archive is non-destructive and needs no confirm.
- Reason: Acceptance requires Delete last and destructive; confirm dialog uses
  platform destructive role for the Delete button.
