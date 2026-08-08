# Home Session Actions

## User Goal

From the home Session List, long-press a session and run Rename, Fork, Archive,
or Delete without opening Session Chat. Tap still opens the session.

## In Scope

- Long-press a home Session List row → action sheet (bottom sheet) with menu
  order: **Rename → Fork → Archive → Delete** (Delete last, destructive).
- **Rename:** name dialog → `SessionRepository.updateSession` → row updates in
  place via Live Store; stay on home.
- **Fork:** name dialog with suggested title `Fork of {currentTitle}` (fallback
  `Forked session`) → whole-session fork → **Open forked session** / **Stay
  here**. Open navigates into the forked session; Stay remains on home with the
  list refreshed.
- **Archive:** immediate, no confirmation → session leaves the active list;
  stay on home.
- **Delete:** confirm dialog with session title → permanent delete → row gone;
  stay on home.
- On failure, stay on home and show clear error copy (same product meaning as
  Session Chat: couldn’t rename / fork / archive / delete).
- Reuse existing Session Repository mutations and dialog/copy patterns from
  Session Chat / Commands.
- Hide Fork when inapplicable the same way Commands does (no visible user
  message / not forkable), resolved via a per-row message fetch when the sheet
  opens.
- Allow Rename, Archive, and Delete while the session is busy.

## Out Of Scope

- Swipe actions, pin, Review changes, Session context, Copy session ID.
- Unarchive / archived-session browser.
- Message-level “Fork from here” or Session Chat overflow fork wiring (OPE-81).
- Bulk actions.
- Changing Session Chat overflow entry points (they remain).

Related: [Session Rename](../session-rename/plan.md),
[Session Archive and Delete](../session-archive-delete/plan.md),
[Slash & @ Commands](../slash-and-at-commands/plan.md) (fork dialog path),
[Project List Home](../project-list-home/plan.md).

## Server APIs Involved

- `PATCH /session/{id}` — rename title; archive via `{ "time": { "archived": … } }`.
- `DELETE /session/{id}` — permanent delete.
- `POST /session/{id}/fork` — whole-session fork (`messageID` omitted).
- `GET` message list for the target session — Fork visibility gate only.
- Foreground Live Store: `session.updated` / `session.deleted` / `session.created`
  refresh the home list.

Expected logs: action start/success/failure with session id (same style as
Session Chat mutations).

## Domain Objects

- `Session` / `SessionSummary` / home list row models.
- `Message` (+ `ClientSlashActionCatalog` visibility) for Fork gate.
- Session Repository mutations already used from Session Chat / Commands.

## Screen Model

**UI state (home-owned):**

- Action sheet target (`sessionId`, `title`, `worktree`) and visibility.
- `canFork` (false until message fetch confirms ≥1 visible user message).
- Rename dialog draft / submitting / error.
- Fork dialog draft / submitting / error; fork result Open/Stay.
- Delete confirm visibility; lifecycle in-flight.
- Home snackbar for archive/delete/fork failures (rename errors stay in dialog).
- Pending open target for fork **Open** (reuse existing create-session open path).

**Actions:**

- Long-press session row; dismiss sheet; choose Rename / Fork / Archive / Delete.
- Confirm/cancel rename; confirm/cancel fork; Open/Stay after fork; confirm/cancel
  delete.

## Native Navigation Entry Points

- iOS: `ProjectListHomeView` / `SessionList` long-press → sheet + dialogs on home.
- Android: `MainScreen` / `SessionList` long-press → sheet + dialogs on home.
- Fork **Open** uses existing pending-open → Session Chat navigation.

## Error States

- Rename failure: keep rename dialog; show error in dialog.
- Fork failure: keep fork name dialog; show error in dialog.
- Archive / Delete failure: stay on home; snackbar
  “Couldn't archive session.” / “Couldn't delete session.”
- Fork gate fetch failure: hide Fork (treat as not forkable); other actions remain.

## Permission Request Behavior

Not applicable — no permission approval UI in this slice.

## Design-System Components

| Element | Classification |
| --- | --- |
| `OpenCodeListItem` long-press | Existing component — extend with optional long-press |
| `OpenCodeBottomSheet` + entry | Existing — extend entry with optional `destructive` |
| `OpenCodeDialog` + text field | Existing |
| `OpenCodeSnackbarHost` | Existing (home already hosts project-removal snackbar) |
| Session List row wiring | Existing `SessionList` — add long-press callback |
