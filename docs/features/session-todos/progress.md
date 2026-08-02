# Session Todos Progress

## Shared Planning

- Status: complete for OPE-96 (canonical feature docs).
- Product rules locked in `CONTEXT.md` and parent OPE-60.
- Prototype C (pill + sheet) accepted; chrome is OPE-97.
- Feature plan, decisions, parity gaps, OPE-95 task plan, and durable
  prototype verdict live under this directory.
- Local integration branch: `feature/ope-60-session-todos` (OPE-96 + OPE-95).

## Design System

- Not applicable for OPE-95 (no chrome). OPE-97 will classify primitives.

## iOS

- Status: in progress — Session Todo List sync (OPE-95) merged onto
  `feature/ope-60-session-todos` (not yet on `main`).
- Done: real `SessionTodo` / status models, DTO + adapter mapping,
  `SessionTodoLiveStore` (GET on live, SSE replace, idle clear), Session Chat
  UI state fields (items / visible / preview / N/M), mapping unit tests.
- Remaining for OPE-95: close acceptance / smoke on device with a live session.

## Android

- Status: in progress — Session Todo List sync (OPE-95) merged onto
  `feature/ope-60-session-todos` (not yet on `main`).
- Done: real models/DTO/adapter, `DefaultSessionTodoLiveStore`, Session Chat
  fields, mapping + live-store unit tests.
- Remaining for OPE-95: close acceptance / smoke on device with a live session.

## Blockers

- None for OPE-96.
- OPE-97 blocked by OPE-95.

## Remaining Work

- [ ] OPE-95 Session Todo List sync — finish acceptance and merge via OPE-60
- [ ] OPE-97 Session Todos pill + sheet in Session Chat

## Completed

- [x] OPE-96 Session Todos feature docs (`plan`, `progress`, `decisions`,
      `parity-gaps`, OPE-95 task plan, prototype verdict)
- [x] Prototype C verdict (throwaway branch `prototype/ope-60-session-todos-ui`)
- [x] Domain terms in `CONTEXT.md`
- [x] Research `docs/research/opencode-session-todos.md` (local research artifact)
- [x] OPE-95 core sync implementation on iOS and Android (on OPE-60 branch)
