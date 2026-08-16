# Session Todos Progress

## Shared Planning

- Status: OPE-168 in progress — unfinished Session Todos stay visible after idle.
- Product rules locked in `CONTEXT.md` and parent OPE-60.
- Prototype C (pill + sheet) accepted; chrome shipped in OPE-97.
- Feature plan, decisions, parity gaps, and OPE-95/OPE-97/OPE-168 task plans
  live under this directory.

## Design System

- Session Todos Pill added (`OpenCodeSessionTodosPill`) with docs, tokens,
  galleries, and previews on both platforms.
- Session Todo Item added (`OpenCodeSessionTodoItem`) for view-only status rows
  in the sheet (galleries + docs); sheet shell stays `OpenCodeBottomSheet`.

## iOS

- Status: OPE-168 visibility + sync implemented; not merged.
- Done: OPE-95 sync + OPE-97 pill/sheet chrome + idle-visible list.

## Android

- Status: OPE-168 visibility + sync implemented; not merged.
- Done: OPE-95 sync + OPE-97 pill/sheet chrome + idle-visible list.

## Blockers

- None.

## Remaining Work

- [ ] Optional: align sheet progress-line spacing/typography (see parity-gaps)

## Completed

- [x] OPE-96 Session Todos feature docs (`plan`, `progress`, `decisions`,
      `parity-gaps`, OPE-95/OPE-97 task plans, prototype verdict)
- [x] Prototype C verdict (throwaway branch `prototype/ope-60-session-todos-ui`)
- [x] Domain terms in `CONTEXT.md`
- [x] Research `docs/research/opencode-session-todos.md` (local research artifact)
- [x] OPE-95 Session Todo List sync (merged in PR #82; Linear Done)
- [x] OPE-97 pill + sheet chrome on iOS and Android (merged in PR #82)
- [x] Pill last-positive snapshot on both hosts; strip unrelated onboarding /
      keyboard / liquid-glass docs from this branch
- [x] OPE-106 float stack: only visible pills; Scroll XOR Review primary;
      Todos secondary; no spacedBy layout ghosts (Android + iOS)
- [x] OPE-168 keep unfinished Session Todos visible after idle (iOS + Android)
