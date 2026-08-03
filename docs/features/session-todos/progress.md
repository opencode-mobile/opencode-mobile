# Session Todos Progress

## Shared Planning

- Status: implementation merged via PR #82 (`OPE-60 Session Todos`); OPE-96 and
  OPE-95 closed in Linear. OPE-60 auto-closed on merge; OPE-97 stays open for
  device smoke.
- Product rules locked in `CONTEXT.md` and parent OPE-60.
- Prototype C (pill + sheet) accepted; chrome is OPE-97.
- Feature plan, decisions, parity gaps, OPE-95/OPE-97 task plans, and durable
  prototype verdict live under this directory.

## Design System

- Session Todos Pill added (`OpenCodeSessionTodosPill`) with docs, tokens,
  galleries, and previews on both platforms.
- Session Todo Item added (`OpenCodeSessionTodoItem`) for view-only status rows
  in the sheet (galleries + docs); sheet shell stays `OpenCodeBottomSheet`.

## iOS

- Status: shipped on `main` (PR #82); acceptance smoke pending.
- Done: OPE-95 sync + OPE-97 pill/sheet float stack, sheet, permission/question
  sheet dismissal, ~1.5s all-done hold, `sessionTodosFinished` on UI state.
- Remaining: device smoke with a live Session Todo List; then close OPE-97.

## Android

- Status: shipped on `main` (PR #82); acceptance smoke pending.
- Done: OPE-95 sync + OPE-97 pill/sheet float stack, sheet, permission/question
  sheet dismissal, ~1.5s all-done hold, `sessionTodosFinished` on UI state.
- Remaining: device smoke with a live Session Todo List; then close OPE-97.

## Blockers

- None.

## Remaining Work

- [ ] Device smoke with a live Session Todo List; then close OPE-97
- [ ] Optional: align sheet progress-line spacing/typography (see parity-gaps)
- [ ] OPE-106 device check: Scroll to end baseline with/without Todos pill

## Completed

- [x] OPE-96 Session Todos feature docs (`plan`, `progress`, `decisions`,
      `parity-gaps`, OPE-95/OPE-97 task plans, prototype verdict)
- [x] Prototype C verdict (throwaway branch `prototype/ope-60-session-todos-ui`)
- [x] Domain terms in `CONTEXT.md`
- [x] Research `docs/research/opencode-session-todos.md` (local research artifact)
- [x] OPE-95 Session Todo List sync (merged in PR #82; Linear Done)
- [x] OPE-97 pill + sheet chrome on iOS and Android (merged in PR #82; Linear
      open until device smoke)
- [x] Ignore SSE `todo.updated` while idle (keeps idle clear durable)
- [x] Pill last-positive snapshot on both hosts; strip unrelated onboarding /
      keyboard / liquid-glass docs from this branch
- [x] OPE-106 float stack: only visible pills; Scroll XOR Review primary;
      Todos secondary; no spacedBy layout ghosts (Android + iOS)
