# Permission Approval

## User Goal

Understand and resolve a permission request when opencode pauses work in a
session, without returning to the desktop client.

## In Scope

- Fetch and reconcile pending permission requests.
- Consume `permission.asked` and `permission.replied` foreground events.
- Surface the first pending request for the open session or a descendant.
- Show the permission category and every server-provided pattern.
- Allow once or deny, with in-flight, failure, stale-request, and queue states.
- Keep product behavior synchronized across iOS and Android.

## Out Of Scope

- Allow always, remember, auto-accept, or permission-rule editing.
- Push notifications, background SSE, or resolved permission history.
- Question-request UI.

## Server APIs And Events

- `GET /permission`
- `POST /session/{sessionID}/permissions/{permissionID}`
- `permission.asked`
- `permission.replied`

The response wire values are `once` and `reject`. See [web-spec.md](web-spec.md)
for the upstream evidence and recovery contract.

## Domain Objects

- `PermissionRequest`: id, session id, category, patterns, metadata, always
  patterns, and optional tool correlation.
- `PermissionDecision`: `allowOnce` or `deny`.
- `PermissionLiveStore`: complete foreground pending-request state.
- `PermissionApprovalState`: visible request, queue position/count, submission,
  and recoverable error.

## Screen Model

Session Chat projects the first pending request in its session tree. Explicit
actions are allow once, deny, and retry. A decision captures the request and
session identifiers so a late tap cannot target the next queued item.

## UI States

- No pending request: composer renders prompt entry.
- Pending: the same composer shell renders permission approval content instead
  of prompt entry.
- Multiple pending: first request plus queue position/count.
- Submitting: both decisions disabled with progress feedback.
- Error: prompt remains and exposes retry through the same decisions.
- Stale: refresh pending requests; remove only if the server no longer lists it.

## Error And Recovery Behavior

Initial connection and every foreground reconnect reconcile `GET /permission`.
Ask/reply reducers are idempotent. A response is not removed optimistically;
the reply event or a successful full reconciliation is authoritative.

## Permission Behavior

- **Allow once** serializes as `once`.
- **Deny** serializes as `reject`.
- Mobile does not expose `always` or persist a decision.

Permission prompts are an awareness/control mechanism, not a security sandbox.

## Design-System Components

- Extend `SessionChatComposerView` with a permission-approval state on both
  platforms so prompt entry and approval share one shell implementation.
- Reuse `OpenCodeButton`, icon catalog, semantic error color, body,
  caption, code, and button typography.
- Do not introduce a separate permission container or duplicate composer shell.

## Navigation Entry Points

The approval surface appears in the existing iOS and Android Session Chat
screens. A descendant-session request is reachable from its open parent.

## Expected Logs

- API: pending-list request and permission response result without credential or
  command-pattern logging.
- SSE: request added/updated and reply removed by identifiers.
- Screen model: visible request changes and response failures.
