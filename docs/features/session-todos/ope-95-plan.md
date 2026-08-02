# OPE-95 — Session Todo List sync

## Goal

Make the open session’s Session Todo List correct and live on the Session Chat
screen model on iOS and Android — without pill/sheet chrome.

## In scope

1. Replace stub `SessionTodo` / `TodoDTO` with real fields (`content`, `status`,
   `priority`). List order is identity.
2. Map `GET /session/{id}/todo` through repository + domain adapter (pass
   directory on both platforms).
3. `SessionTodoLiveStore`: GET when live (force if cached), apply SSE
   `todo.updated` as full replace for the matching session, clear on idle,
   force-GET after reconnect while live / on next live.
4. Pure presentation helpers: active preview, `N/M` progress, finished,
   visible-while-live.
5. Expose fields on Session Chat UI state (items, visible, preview, progress).
6. Unit tests for mapping, sync, and presentation.

## Out of scope

- Pill / sheet UI (OPE-97)
- Edit / check-off
- Priority display or sort
- Brief all-done close animation (expose finished; chrome owns timing)

## Live definition

Session is live when busy/retrying (`sessionStatusLabel != nil`) **or** blocked
on a pending permission or question for the open session tree.

## Approach

- ViewModel computes `isLive` and drives `SessionTodoLiveStore.setLive`.
- Live store owns GET / SSE replace / idle clear / reconnect force-GET.
- No design-system work in this ticket.
