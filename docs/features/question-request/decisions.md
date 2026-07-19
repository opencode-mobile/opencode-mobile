# Question Request — Decisions

Grilling lock (2026-07-19). Upstream evidence:
`docs/research/opencode-question-tool.md`.

## Name the live ask a question request

Canonical product term is **question request**. It is parallel to permission
request, distinct from a `question` ToolPart in the timeline, and not folded
into permission approval.

User **dismiss** maps to the server reject route. Do not call the user action
reject or deny in product copy.

## Own it as its own Feature Slice

Live answering lives under `docs/features/question-request/`, not as an
extension of Permission Approval or a child of Assistant Tool Parts.
Permission Approval stays capability gating; Assistant Tool Parts keep timeline
hide rules and completed-card chrome.

## Make question request a composer state

While a question request is the visible blocker, replace the composer’s inner
prompt-entry content inside the shared `OpenCodeSessionComposer` shell (same
pattern as permission approval). Do not use a Bottom Sheet, full-screen flow,
or timeline card for the live ask.

Draft prompt text is preserved and returns after the request resolves. In-memory
answer drafts may be keyed by request id for the foreground session; do not
persist drafts to disk.

## Prefer permission when both are pending

If the session tree has both a pending permission request and a pending
question request, show the permission request first. Web can stack both docks;
mobile shows one blocker and chooses the safer queue (matches TUI).

## Use the same session-tree picker as permissions

Project snapshots through the open session first, then descendants in
parent-first order. One visible question request at a time. Navigating away
leaves the request pending and implies no decision.

## Block normal prompt send while blocked

The user cannot send a normal Session Chat prompt while a question request (or
a higher-priority permission request) is the visible composer state.

## Wizard UX with explicit Submit

Multi-question requests use a one-at-a-time wizard (Back / Next / Submit).
Support `multiple: true` and custom text when `custom !== false` (default
allow). Always require an explicit Submit, including single-question
single-select (match web; do not auto-reply on option tap like TUI).

Dismiss is an explicit control. Swipe or navigation must not dismiss.

## Keep timeline rules from Assistant Tool Parts

Hide pending/running `question` ToolParts. Completed cards stay on OPE-50
collapsed chrome for this slice. Dismissed tools use soft “Questions dismissed”
presentation rather than a harsh generic error card when the server error text
indicates dismiss. Expanding completed Q→A in a sheet is a follow-up, not v1
blocking work.

## Prefer instance `/question` routes and list bootstrap

Use `GET /question`, `POST /question/{requestID}/reply`, and
`POST /question/{requestID}/reject`. On connect/reconnect, the list replaces the
pending map. Drop orphan pending rows when the correlated ToolPart completes or
errors without a matching reply/reject event.

Foreground SSE only. No push, no remember-answer, no minimize-without-answer in
v1.
