# Keyboard Dismissal

Normative defaults for dismissing (and restoring) the software keyboard across
OpenCode Mobile. Use this document when adding or changing any text-entry
surface. Features may diverge only with a one-line note in that feature's
`decisions.md` (and `parity-gaps.md` when the platforms differ temporarily).

Related: [text input](components/text-input.md),
[session chat composer](components/session-chat-composer.md),
[bottom sheet keyboard presentation](divergences.md#bottom-sheet-form-keyboard-presentation),
[chat scroll dismiss gesture](divergences.md#chat-timeline-scroll-keyboard-dismiss).

## Principles

1. Prefer platform primitives over custom gestures (`scrollDismissesKeyboard` /
   `@FocusState` on iOS; IME insets + `FocusManager` on Android).
2. Focus is view chrome, never Screen Model or domain state.
3. Dismiss when the user leaves editing intent; do not dismiss when the gesture
   is part of editing.
4. Give each surface one primary dismiss path; add secondary paths only when the
   primary cannot cover the job.
5. Keep the same intent across platforms; exact gesture fidelity may differ.

## Surface taxonomy

| Surface type | Examples | Default dismiss strategy |
| --- | --- | --- |
| Form / dialog / sheet fields | Connection setup, rename, short dialogs | Submit label (`next` / `done`) clears focus. If the body scrolls, also use immediate scroll dismiss. |
| Chat / timeline + composer | Session Chat | Timeline uses interactive scroll dismiss on iOS (user-drag clear-focus on Android; not programmatic follow). Composer owns focus state. Clear on send, modal open, and field unmount / permission swap. |
| Search / filter bars | Model filter in a sheet | Search/Done submit clears focus; clear when the hosting sheet dismisses. |
| Read-only | Diffs, status, lists without fields | No policy. |
| Transient overlay over an editor | Pickers, overflow, permission shell replacing the field | Clear focus when the overlay takes over or the field unmounts. |

## On-the-fly checklist

Ask in order when adding UI:

1. Is there a text field? If no, stop.
2. What is the user doing: editing a form, composing a message, or filtering?
3. Which gesture means “done typing / want more canvas”?
   - Scrolling the primary content canvas → scroll dismiss (mode from taxonomy)
   - Explicit submit / Done / send → clear focus
   - Opening another surface that covers or replaces the field → clear on present
4. Would dismiss fight a real interaction (chip swipe, message actions, caret,
   text selection)? Exclude that gesture from dismiss.
5. Does this screen need autofocus later (restore draft, validation error)? Keep
   a view-local focus binding or requester; still not in the ViewModel.

## Hard rules

- Do not use `UIApplication.resignFirstResponder`, activity-wide tap-to-dismiss,
  or equivalent global hacks as the default policy. Last-resort escape hatches
  need a code comment and a feature decision note.
- Do not store focus or keyboard visibility in Screen Models.
- Do not dismiss from a gesture that is itself an editor control (for example
  horizontal model/mode chip scrolling).
- Do clear focus when the field's owning UI is replaced (prompt → permission) or
  when navigating away.
- Record intentional platform differences in
  [`divergences.md`](divergences.md). Record temporary mismatches in the
  feature's `parity-gaps.md`.

## Default product choices

| Situation | Default | Override |
| --- | --- | --- |
| Chat timeline scroll | Interactive on iOS; clear-on-scroll-start on Android | Fall back to immediate on iOS if sticky-follow / keyboard animation fights interactive dismiss |
| Form / scrolling sheet body | Immediate scroll dismiss + submit labels | Submit labels only for short non-scrolling dialogs |
| Send prompt | Dismiss keyboard | Feature may keep keyboard for rapid follow-ups with a decision note |
| Restore draft into composer | Refocus (keyboard up) | Text-only restore only when restore is not “resume editing” |
| Focus ownership | Inside the input/composer component | Optional parent `isFocused` binding only when the parent must drive focus |
| Tap empty canvas to dismiss | Not a default | Allowed only as a written feature exception |
| Cross-platform parity | Same intent | Same gesture not required |

## Chat specialization

Session Chat (and future timeline + composer screens) apply the taxonomy as:

- Timeline `ScrollView` / list: `.scrollDismissesKeyboard(.interactively)` on
  iOS; Android clears focus on **user drag** of the timeline (not sticky-follow
  or programmatic scroll-to-end).
- Composer owns `@FocusState` / Compose focus; clear on send, picker/sheet open,
  and permission-state swap.
- Horizontal selector chip row must not dismiss (`scrollDismissesKeyboard(.never)`
  or equivalent).
- Restore-to-composer refocuses the prompt field.

Prefer keeping the composer as a sibling of the timeline (not a bottom
`safeAreaInset` that must track interactive keyboard drag) unless a future
layout decision explicitly accepts that trade-off.

## Form specialization

`OpenCodeTextInput` already clears focus on `done` and advances on `next`.
Scrolling form sheets should add immediate scroll dismiss on the scrollable body
without changing submit-label behavior. See
[bottom sheet form keyboard presentation](divergences.md#bottom-sheet-form-keyboard-presentation)
for how sheets follow the IME without resizing the presenting screen.

## Retrofit guidance

1. Land this policy for new work immediately.
2. Implement the chat specialization on Session Chat when keyboard stuck-state
   is being fixed.
3. Touch existing forms and sheets only when they are broken or already under
   edit; do not audit every field in one pass.

## Agent / PR checklist

- [ ] Surface type chosen from the taxonomy
- [ ] Primary dismiss path matches that type
- [ ] Editor-control gestures excluded from dismiss
- [ ] Focus kept view-local (no Screen Model focus flags)
- [ ] Same intent on the other platform, or a divergence / parity-gap note
