# Question Request

## User Goal

Answer or dismiss structured questions when opencode pauses a session for user
input, without returning to the desktop client.

## In Scope

- Fetch and reconcile pending question requests.
- Consume `question.asked`, `question.replied`, and `question.rejected`
  foreground events.
- Surface the first pending question request for the open session or a
  descendant (same session-tree walk as permission approval).
- Multi-question wizard (Back / Next / Submit) with single-select, multi-select,
  and custom text when allowed.
- Explicit Submit for every request, including single-question single-select.
- Explicit Dismiss (maps to server reject); navigation away does not dismiss.
- Block normal prompt send while a question request is the visible blocker.
- When both a permission request and a question request are pending, show the
  permission request first.
- Keep in-flight `question` ToolParts hidden in the timeline; leave completed
  card chrome to Assistant Tool Parts.
- Keep product behavior synchronized across iOS and Android.

## Out Of Scope

- Push notifications, background SSE, or guaranteed delivery when backgrounded.
- Minimize/collapse without answering (web-only affordance).
- Persistent “remember this answer” (not in the server model).
- Merging question and permission into one interruption type or UI model.
- Expanding completed question cards (Bottom Sheet Q→A) — optional follow-up.
- Experimental v2 `/api/session/{sessionID}/question/*` routes unless generated
  clients standardize on them later.

## Server APIs And Events

- `GET /question`
- `POST /question/{requestID}/reply` with `{ "answers": string[][] }`
- `POST /question/{requestID}/reject`
- `question.asked`, `question.replied`, `question.rejected`

Upstream evidence: `docs/research/opencode-question-tool.md`.

## Domain Objects

- `QuestionRequest`: id (`que…`), session id, questions, optional tool
  correlation (`messageID`, `callID`).
- `QuestionInfo`: question text, header, options (`label`, `description`),
  optional `multiple`, optional `custom` (default allow when absent).
- `QuestionAnswer`: selected labels for one question (`string[]`).
- `QuestionLiveStore`: complete foreground pending-request state.
- `QuestionApprovalState` (Screen Model projection): visible request, wizard
  tab, draft answers, submission, and recoverable error.

## Screen Model

Session Chat projects the first pending question request in its session tree
when no permission request is visible. Explicit actions are select/toggle
option, edit custom text, go back/next, submit, dismiss, and retry. A submit or
dismiss captures the request id so a late tap cannot target the next queued
item.

## UI States

- No pending question (and no higher-priority permission): composer renders
  prompt entry (or permission approval when that is pending).
- Pending question: the same composer shell renders question-request content
  instead of prompt entry.
- Multi-question: wizard progress with Back / Next; Submit on the last
  question when all prior answers are valid.
- Submitting: actions disabled with progress feedback.
- Error: prompt remains and exposes retry through the same actions.
- Stale: refresh pending requests; remove only if the server no longer lists it.

## Error And Recovery Behavior

Initial connection and every foreground reconnect reconcile `GET /question`.
Ask/reply/reject reducers are idempotent. A request is not removed
optimistically; the reply/reject event or a successful full reconciliation is
authoritative. A `404` on reply/reject reloads the pending list. Orphan pending
entries whose correlated ToolPart has completed or errored are dropped.

## Permission Behavior

Not applicable. Question Request does not approve tool capabilities. When both
queues are non-empty, Permission Approval wins visibility.

## Design-System Components

- Extend the Component Collection `OpenCodeSessionComposer` so prompt entry,
  permission approval, and question request share one shell (semantic state
  API; no repositories or transport DTOs on the component).
- Reuse existing buttons, typography, and semantic outline variants; add
  question-specific chrome through the design-system workflow before Session Chat
  depends on it.
- Do not introduce a separate question container or duplicate composer shell.
- Do not place the live ask in a Bottom Sheet or the timeline.

## Navigation Entry Points

The ask surface appears in the existing iOS and Android Session Chat screens. A
descendant-session request is reachable from its open parent.

## Expected Logs

- API: pending-list request and reply/reject result without credential logging.
- SSE: request added/updated and reply/reject removed by identifiers.
- Screen model: visible request changes, wizard tab changes, and response
  failures.
