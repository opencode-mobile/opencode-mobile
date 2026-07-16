# Session Archive and Delete

## User Goal

Archive or permanently delete the open session from Session Chat so it leaves
the active list (archive) or is removed entirely (delete), then return home.

## In Scope

- Entry only from Session Chat overflow: **Archive** and **Delete**, after the
  existing utility items, with Delete last and destructive emphasis.
- **Archive**: immediate, no confirmation; `SessionRepository.archiveSession`
  via `PATCH /session/{id}` with `{ "time": { "archived": <epochMs> } }` and the
  Session Chat worktree (`directory`) binding.
- **Delete**: confirm with Cancel / Delete; body uses the session title; then
  `SessionRepository.deleteSession`.
- Wait for server success before dismissing Session Chat back to home; disable
  archive/delete while in flight.
- On failure, stay on Session Chat and show clear error copy
  (“Couldn’t archive session.” / “Couldn’t delete session.”).
- Same actions for child sessions and while a prompt is active or a permission
  request is pending.
- Domain `Session` carries `archivedAtEpochMs`; home and live session lists
  exclude archived sessions client-side.
- Live Store / list refresh treats archive like removal from the active list
  (alongside existing delete / `session.deleted` handling).

## Out Of Scope

- Unarchive, archived-session browser, or restore.
- Archive or delete from the home/session list (swipe, long-press, overflow).
- Neighbor/parent session navigation after success (always dismiss to home).
- Undo toasts, bulk archive, share, or rename from this menu.
- Perfect remote handling when another client archives/deletes the open session
  (polish follow-up).

Related: [Session Chat](../session-chat/plan.md),
[Session Repository](../session-repository/plan.md),
[Session Rename](../session-rename/plan.md).

## Server APIs Involved

- `PATCH /session/{id}` with body `{ "time": { "archived": <epochMs> } }`
  (optional `directory` query).
- `DELETE /session/{id}` → boolean (optional `directory` query).
- Foreground observation: `session.updated` / `session.deleted` refresh home
  lists; client filters any session with non-null `time.archived`.

Expected logs: archive/delete start, success with session id, failure with
error message (same style as other Session Chat mutations).

## Domain Objects

- **session** — includes `archivedAtEpochMs` for list filtering.
- **archived session** — excluded from active lists; not browsable in v1.
- **archive** / **delete (session)** — distinct lifecycle actions.
- **Session Repository** — `archiveSession`, `deleteSession`.
- **Live Store** — refreshes lists; archived rows filtered out after reload.

## Screen Model structure

Archive and delete are owned by the Session Chat regional model path: UI actions
go through the Session Chat facade into SessionInfo (or equivalent session-info
region) which calls Session Repository. Views must not perform HTTP.

## UI States

- Session Chat loaded; overflow available.
- Lifecycle action idle.
- Lifecycle action in flight (Archive/Delete disabled).
- Delete confirmation presented (UI-local dialog).
- Lifecycle error shown on Session Chat; session still open.
- Leave-session signal after successful archive or delete.

## User Actions

- Open overflow → **Archive** (immediate).
- Open overflow → **Delete** → Cancel or Delete.
- Observe dismiss to home on success, or error copy on failure.

## Native Navigation Entry Points

- iOS: Session Chat `OpenCodeTopBarOverflowHost` entries.
- Android: Session Chat `OpenCodeTopBarOverflowHost` entries.
- Success dismisses Session Chat back to home (existing back/dismiss path).

## Error States

- Archive failure: stay on Session Chat; “Couldn’t archive session.”
- Delete failure: stay on Session Chat; “Couldn’t delete session.”
- Reuse the existing Session Chat error banner surface.

## Permission Behavior

- Not applicable. Archive/delete do not create or resolve permission requests.
- Actions remain available while a permission request is pending.

## Design-System Components Needed

| Element | Category | Notes |
| --- | --- | --- |
| Overflow entries | Existing + small extension | `OpenCodeOverflowMenuEntry` — Archive / Delete; add optional destructive emphasis for Delete |
| Delete confirm dialog | Feature-local | Platform alert (Cancel / Delete); title “Delete session”, body with session title |
| Error banner | Existing | Session Chat mutation error text |
| Catalog `archive` icon | Done | Session Chat overflow Archive uses Material `archive` |
