# Session Rename

## User Goal

Rename the open session from Session Chat so the title shown on that screen
matches the name the user wants, without leaving the session.

## In Scope

- Entry only from Session Chat overflow: **Rename session**.
- Show the session title as the Session Chat top bar primary title (not the
  fixed chrome label `"Session"`).
- Open a modal dialog with the current title prefilled in `OpenCodeTextInput`.
- Confirm with **Save**, dismiss with **Cancel**.
- Trim whitespace; empty after trim does not call the API; unchanged title
  dismisses without an API call.
- On success, update the Session Chat title immediately; stay on the same
  session (id unchanged).
- Allow rename for child sessions the same as parent sessions.
- Allow rename while a prompt is running or a permission request is pending;
  disable only while a rename submit is in flight.
- Call `SessionRepository.updateSession` with the Session Chat worktree
  (`directory`) binding.
- Apply `session.updated` title for the open session so live title changes stay
  in sync with the top bar.
- On failure, keep the previous title and surface an error consistent with
  Session Chat / dialog error handling.

## Out Of Scope

- Tap-title inline editing on the top bar (desktop web has this; mobile does
  not in this slice).
- Archive, delete, share, or other session lifecycle actions from this dialog.
- Client-side max title length unless the server contract documents one.
- Guaranteed home-list refresh beyond existing Live Store / list observation
  behavior (follow-up only if home does not already pick up session updates).

Home-list rename via long-press is owned by
[Home Session Actions](../home-session-actions/plan.md) (OPE-120).

Related: [Session Chat](../session-chat/plan.md),
[Session Repository](../session-repository/plan.md),
[Home Session Actions](../home-session-actions/plan.md).

## Server APIs Involved

- `PATCH /session/{id}` with body `{ "title": "<string>" }` (and `directory`
  query when scoping to the open Added Project worktree).
- Foreground observation of `session.updated` for the open session's title.

Expected logs: rename submit start, success with session id, and failure with
error message (same logging style as other Session Chat mutations).

## Domain Objects

- **session** (id, title) — title is the renamed property.
- **child session** — same rename behavior as any session.
- **Session Repository** — performs the update.
- **foreground event stream** / Live Store — may deliver `session.updated`
  title changes for the open session.

## Screen Model structure

Rename is owned by the Session Chat regional model path: UI actions go through
the Session Chat facade into SessionInfo (or equivalent session-info region)
which calls Session Repository. Views must not perform HTTP.

## UI States

- Session Chat loaded with current `sessionTitle` in the top bar.
- Rename dialog hidden.
- Rename dialog editing (draft text).
- Rename dialog submitting (Save disabled / in flight).
- Rename dialog error (previous title retained until a later success).

## User Actions

- Open overflow → **Rename session**.
- Edit draft title.
- **Save** / **Cancel**.
- Observe top bar title update after success or from `session.updated`.

## Native Navigation Entry Points

- iOS: Session Chat `OpenCodeTopBarOverflowHost` entry.
- Android: Session Chat `OpenCodeTopBarOverflowHost` entry.
- No new navigation destination; dialog overlays Session Chat.

## Error States

- Network / server failure on PATCH: keep prior title; show error on the dialog
  or via the same Session Chat error pattern already used for mutations.
- Empty trimmed title: no API call; keep dialog open with a light validation
  error or equivalent clear feedback.

## Permission Behavior

- Not applicable. Rename does not create or resolve permission requests.

## Design-System Components Needed

| Element | Category | Notes |
| --- | --- | --- |
| Top bar title binding | Existing | `OpenCodeTopBar` — bind primary title to session title |
| Overflow entry | Existing | `OpenCodeOverflowMenuEntry` — **Rename session** |
| Text field | Existing | `OpenCodeTextInput` |
| Rename dialog chrome | Feature-local | Platform modal dialog (Cancel / Save); not a new Component Collection primitive unless a shared prompt dialog is introduced later |
| Bottom sheet for rename | Not used | Overflow opens a dialog, not `OpenCodeBottomSheet` |
