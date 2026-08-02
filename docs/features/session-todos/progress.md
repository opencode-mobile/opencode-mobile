# Session Todos Progress

## Shared Planning

- Status: complete for OPE-96 (canonical feature docs).
- Product rules locked in `CONTEXT.md` and parent OPE-60.
- Prototype C (pill + sheet) accepted; chrome is OPE-97.
- Feature plan, decisions, parity gaps, OPE-95/OPE-97 task plans, and durable
  prototype verdict live under this directory.
- Local integration branch: `feature/ope-60-session-todos`.

## Design System

- Session Todos Pill added (`OpenCodeSessionTodosPill`) with docs, tokens,
  galleries, and previews on both platforms.
- Sheet status rows remain feature-local (checkbox / indeterminate /
  strikethrough dock semantics are not covered by List Item).

## iOS

- Status: in progress on `feature/ope-60-session-todos`.
- Done: OPE-95 sync + OPE-97 pill/sheet float stack, sheet, permission/question
  sheet dismissal, ~1.5s all-done hold, `sessionTodosFinished` on UI state.
- Remaining: device smoke with a live Session Todo List; close OPE-95/OPE-97
  acceptance for the OPE-60 PR.

## Android

- Status: in progress on `feature/ope-60-session-todos`.
- Done: OPE-95 sync + OPE-97 pill/sheet float stack, sheet, permission/question
  sheet dismissal, ~1.5s all-done hold, `sessionTodosFinished` on UI state.
- Remaining: device smoke with a live Session Todo List; close OPE-95/OPE-97
  acceptance for the OPE-60 PR.

## Blockers

- None for OPE-96 / OPE-97 implementation on this branch.

## Remaining Work

- [ ] OPE-95 Session Todo List sync — finish acceptance via OPE-60 PR
- [ ] OPE-97 Session Todos pill + sheet — device smoke / close acceptance
- [ ] Parent OPE-60 PR when ready

## Completed

- [x] OPE-96 Session Todos feature docs (`plan`, `progress`, `decisions`,
      `parity-gaps`, OPE-95/OPE-97 task plans, prototype verdict)
- [x] Prototype C verdict (throwaway branch `prototype/ope-60-session-todos-ui`)
- [x] Domain terms in `CONTEXT.md`
- [x] Research `docs/research/opencode-session-todos.md` (local research artifact)
- [x] OPE-95 core sync implementation on iOS and Android
- [x] OPE-97 pill + sheet chrome on iOS and Android (this branch)
