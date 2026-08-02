# Session Todos Progress

## Shared Planning

- Status: in progress for OPE-95 Session Todo List sync.
- Product rules locked in `CONTEXT.md` and parent OPE-60.
- Prototype C (pill + sheet) accepted; chrome is OPE-97.
- Feature plan + OPE-95 task plan landed under this directory.

## Design System

- Not applicable for OPE-95 (no chrome). OPE-97 will classify primitives.

## iOS

- Status: in progress — Session Todo List sync (OPE-95).
- Done: real `SessionTodo` / status models, DTO + adapter mapping,
  `SessionTodoLiveStore` (GET on live, SSE replace, idle clear), Session Chat
  UI state fields (items / visible / preview / N/M), mapping unit tests.
- Remaining: optional live-store unit tests parity; smoke on device with a live
  session.

## Android

- Status: in progress — Session Todo List sync (OPE-95).
- Done: real models/DTO/adapter, `DefaultSessionTodoLiveStore`, Session Chat
  fields, mapping + live-store unit tests (passing).

## Blockers

- None for OPE-95.
- OPE-97 blocked by OPE-95.

## Remaining Work

- [ ] OPE-95 finish: verify Session Chat wiring under a live turn; close
      acceptance checklist
- [ ] OPE-97 Session Todos pill + sheet in Session Chat
- [ ] OPE-96 Session Todos feature docs polish / reconciliation

## Completed

- Prototype C verdict (throwaway branch `prototype/ope-60-session-todos-ui`)
- Domain terms in `CONTEXT.md`
- Research `docs/research/opencode-session-todos.md`
- OPE-95 core sync implementation on iOS and Android (this branch)
