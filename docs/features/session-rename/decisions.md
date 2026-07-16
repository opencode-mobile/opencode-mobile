# Session Rename Decisions

## Session-screen overflow only

- Decision: Rename is available only from Session Chat overflow in this slice.
- Reason: Keeps v1 entry clear; home-list rename can be a later surface without
  changing the PATCH contract.

## Dialog, not inline title edit

- Decision: Use `OpenCodeDialog` with an in-field `OpenCodeTextInput` hint, not
  tap-to-edit on the top bar and not a bottom sheet.
- Reason: Matches TUI’s prompt pattern and fits mobile top-bar chrome; desktop
  web’s inline editor does not map cleanly when the title is also the navigation
  title. Shared Dialog chrome keeps rename aligned with other short confirmations.

## Show title in the top bar

- Decision: Session Chat’s primary top bar title is the session title and updates
  after a successful rename (and from live `session.updated`).
- Reason: Rename must be observable on the screen where it was performed.

## Child sessions are renameable

- Decision: Child sessions use the same rename affordance and rules as parent
  sessions.
- Reason: A child session is still a session with a user-visible title. Desktop
  web blocks rename when a parent id is present; mobile intentionally does not
  copy that restriction.

## Confirmed update, not optimistic

- Decision: Update the displayed title after a successful `PATCH`, not before.
- Reason: Avoids a flash of a title the server rejected; failure keeps the prior
  title with an error.

## Rename independent of run state

- Decision: Rename remains available while the session is busy or a permission
  request is pending; only an in-flight rename submit disables Save.
- Reason: Title is independent of prompt execution and permission resolution.

## Platform-native rename chrome

- Decision: Android uses `AlertDialog`; iOS uses a medium form sheet. Both use
  `OpenCodeTextInput` with Cancel/Save.
- Reason: Keep platform-native modal patterns while sharing product behavior and
  design-system field chrome.
