# Permission Approval — opencode Web Reference Specification

## Research Baseline

This specification records the behavior of the upstream opencode web client at
commit [`34e5809`](https://github.com/anomalyco/opencode/tree/34e58090595d44e3e7cc37498f16753a98627456)
(2026-07-11), inspected on 2026-07-12. It is a behavioral reference for a
future synchronized iOS and Android feature slice, not an instruction to copy
the web layout literally.

Primary upstream sources:

- [`session-permission-dock.tsx`](https://github.com/anomalyco/opencode/blob/34e58090595d44e3e7cc37498f16753a98627456/packages/app/src/pages/session/composer/session-permission-dock.tsx)
  for visible content and actions;
- [`session-composer-state.ts`](https://github.com/anomalyco/opencode/blob/34e58090595d44e3e7cc37498f16753a98627456/packages/app/src/pages/session/composer/session-composer-state.ts)
  for blocking, submission, and error behavior;
- [`session-request-tree.ts`](https://github.com/anomalyco/opencode/blob/34e58090595d44e3e7cc37498f16753a98627456/packages/app/src/pages/session/composer/session-request-tree.ts)
  for parent/child-session selection;
- [`server-session.ts`](https://github.com/anomalyco/opencode/blob/34e58090595d44e3e7cc37498f16753a98627456/packages/app/src/context/server-session.ts)
  for live request insertion and removal;
- [`bootstrap.ts`](https://github.com/anomalyco/opencode/blob/34e58090595d44e3e7cc37498f16753a98627456/packages/app/src/context/global-sync/bootstrap.ts)
  for pending-request recovery during initial synchronization;
- [`permission.tsx`](https://github.com/anomalyco/opencode/blob/34e58090595d44e3e7cc37498f16753a98627456/packages/app/src/context/permission.tsx)
  for response transport and web-only auto-accept behavior;
- [`layout.tsx`](https://github.com/anomalyco/opencode/blob/34e58090595d44e3e7cc37498f16753a98627456/packages/app/src/pages/layout.tsx)
  for off-screen notifications and navigation.

## User Goal

When opencode pauses active work because a configured permission rule evaluates
to `ask`, the user can understand the requested capability and unblock or stop
that specific operation from the mobile Session Chat screen.

## Web Behavior

### Request discovery and lifetime

1. The server emits `permission.asked` on its foreground event stream.
2. Web stores each request under its `sessionID`, inserting by request id and
   reconciling a duplicate event instead of displaying it twice.
3. Session Chat selects the first pending request in the open session or any
   descendant session. A request in the open parent session has priority over a
   request in a child session.
4. Only one permission prompt is presented at a time.
5. The prompt makes the composer region blocked while it is pending.
6. The server emits `permission.replied`; web removes the matching request by
   `requestID`. The next queued request, if any, becomes visible.

Web lists pending requests through `GET /permission` during initial
synchronization and again when enabling auto-accept. Mobile needs the list
endpoint for initial load and reconnect recovery even though mobile v1 does not
implement auto-accept.

### Prompt content

The web prompt is docked above/in place of the composer and contains:

- a warning icon and the title **Permission requested**;
- a localized, permission-specific description when one exists;
- every server-provided `pattern`, rendered as code and allowed to wrap;
- actions **Deny**, **Allow always**, and **Allow once**.

The request contract used by web contains:

| Field | Meaning | Mobile requirement |
|---|---|---|
| `id` | Permission request identifier | Required for response and deduplication |
| `sessionID` | Owning session | Required for routing and response |
| `permission` | Capability/tool category | Required for title/description mapping |
| `patterns[]` | Concrete requested targets or command patterns | Display all, verbatim, as code |
| `metadata` | Tool-specific structured context | Preserve in domain model; display only deliberately supported safe fields |
| `always[]` | Patterns used by an `always` decision | Preserve for compatibility; v1 does not expose `always` |
| `tool.messageID`, `tool.callID` | Originating tool call when present | Preserve for correlation and diagnostics |

### Decisions and mutation behavior

Web submits a response to
`POST /session/{sessionID}/permissions/{permissionID}` with body:

```json
{ "response": "once" | "always" | "reject" }
```

The endpoint returns `200 boolean`; `400` indicates a bad request and `404`
indicates a missing/stale request. The newer non-session alias
`POST /permission/{requestID}/reply` uses `{ "reply": "once" | "always" |
"reject" }`, but mobile's existing session repository is already shaped around
the session-scoped compatibility endpoint.

While a response is in flight, web disables all actions and ignores repeated
taps for the same request. It does not optimistically remove the prompt; the
live `permission.replied` event is authoritative. A failed mutation re-enables
the actions and shows a request-failed message.

### Off-screen attention

If the request belongs to neither the currently open session nor its direct
child, web can play the configured permission sound, send a platform
notification, and show a persistent in-app toast with **Go to session** and
**Dismiss**. Alerts are grouped by directory and session, rate-limited, and
dismissed when a reply arrives or the user navigates to that session.

Mobile v1 should use an in-app pending indicator while foregrounded. Push
notifications and guaranteed background handling remain out of scope.

### Web behavior intentionally excluded from mobile v1

Web exposes **Allow always** and a persisted per-session/per-directory
auto-accept mode. Auto-accept responds `once` automatically, including to
pending requests returned by `GET /permission`.

Mobile v1 must not create persistent or remembered decisions. It therefore
exposes exactly:

- **Allow once** → `response: "once"`;
- **Deny** → `response: "reject"`.

It does not expose **Allow always**, auto-accept, permission-rule editing, or
configuration administration.

## Mobile Product Specification

### Entry and presentation

- Present the permission approval surface inside Session Chat, adjacent to the
  composer, because the session is blocked at the point where the user normally
  continues the conversation.
- The surface is persistent and non-dismissible while the request is pending.
  Navigation away is allowed; dismissing the UI must never imply a decision.
- Show the permission category, supported explanatory copy, and all patterns.
  Long commands and paths wrap and remain selectable/copyable.
- If several requests are pending in the current session tree, show one at a
  time and communicate the remaining count (for example, “1 of 3”).
- Preserve parent/child session behavior from web so work delegated to a child
  can be approved while viewing its parent session.

### State model

The immutable Session Chat state needs:

- pending requests keyed by owning `sessionID` and deduplicated by `id`;
- the selected visible request for the current session tree;
- pending-count information;
- response submission state keyed by request id;
- recoverable response error associated with the request;
- foreground attention state for sessions with unseen requests.

Explicit actions are `allowOnce(requestID)`, `deny(requestID)`,
`retryResponse(requestID)`, and navigation to the owning session. Response
actions must capture both request id and session id so a late tap cannot target
a newly displayed request.

### Synchronization and recovery

- Subscribe to `permission.asked` and `permission.replied` while the app is in
  the foreground.
- On first connection, foreground resume, or SSE reconnect, fetch
  `GET /permission?directory=…` and reconcile the complete pending set before
  treating state as current.
- Insert/update `permission.asked` idempotently.
- Remove only the request named by `permission.replied.requestID`.
- Treat `404` from a response as a stale request: refresh pending permissions,
  remove it if absent, and explain that it was already handled or expired.
- If the connection drops while a response is in flight, do not infer success.
  refresh pending requests after reconnect.

### Safety and accessibility

- Make **Allow once** the visually primary action, but never trigger it via a
  generic composer Send action or an ambiguous swipe.
- Keep **Deny** clearly distinct and accessible without requiring destructive
  color perception.
- Give each action an explicit accessibility label that includes the decision;
  announce submission failure and the arrival of the next queued request.
- Never transform, truncate, or execute displayed patterns. Rendering them is
  untrusted text display.
- Product copy must state that permission approval is an awareness/control
  mechanism, not a sandbox or guarantee of isolation.

## Current Mobile Gap

Both native apps currently have a repository mutation for the session-scoped
endpoint, but neither app has the request domain model, pending-request fetch,
SSE reducers, queue selection, Session Chat UI, foreground attention, or
end-to-end tests.

There is also a contract mismatch:

- Android maps `Approve` to `"approve"` and `Deny` to `"deny"`;
- iOS defines raw decisions `approve`, `deny`, and `once`;
- current opencode expects `"once"`, `"always"`, or `"reject"`.

Before UI work, replace the ambiguous decision model on both platforms with
mobile product terms that serialize **Allow once** as `once` and **Deny** as
`reject`. Do not retain `approve`/`deny` as wire values.

## Required Implementation Slices

1. **Contract correction:** request DTO, response values, pending-list API, and
   typed event payloads.
2. **Live store:** idempotent ask/reply reducers plus initial/reconnect
   reconciliation.
3. **Domain adapter:** safe request projection, session-tree queue selection,
   and mobile-only decision enum.
4. **Design system:** reusable state-aware permission prompt/banner component,
   variants, previews, and galleries before feature composition.
5. **Session Chat integration:** blocking surface, queue count, in-flight and
   error states, and foreground pending indicators.
6. **Validation:** contract tests, reducer/reconnect tests, parent-child queue
   tests, duplicate/late event tests, response failure tests, accessibility
   checks, and device/simulator evidence on both platforms.

## Acceptance Criteria

- A request arriving live appears once in the correct open session tree.
- A pending request missed while disconnected appears after reconciliation.
- The prompt shows its permission category and every pattern without data loss.
- Allow once sends exactly `once`; Deny sends exactly `reject`.
- Repeated taps cannot send duplicate responses.
- Actions remain disabled only while that request is submitting.
- Failure keeps the request actionable and gives a retry path.
- A reply event removes only its matching request and advances the queue.
- Child-session requests are reachable from the open parent session.
- No mobile UI or domain API offers `always`, remember, or auto-accept.
- iOS and Android match in behavior, copy semantics, accessibility intent, and
  recovery behavior; intentional visual differences are documented.

## Out of Scope

- Persistent permission decisions or rule editing;
- per-session or per-directory auto-accept;
- push notification delivery or background SSE;
- permission history after requests are resolved;
- treating permission approval as a security sandbox;
- question-request UI, which is a separate server interaction despite sharing
  some web presentation infrastructure.
