# Session Chat Decisions

## Start with local Screen Model scaffold

- Date: 2026-05-07
- Decision: first Session Chat implementation uses local/mock state for messages
  and composer actions on both platforms.
- Reason: unblock session-to-chat navigation and shared interaction framing before
  server integration is introduced.
- Revisit when: session history, prompt send, and stream endpoints are connected.

## Use local composer selectors in scaffold

- Date: 2026-05-09
- Decision: Session Chat composer shows local selector chips for model, mode, and
  Thinking Effort before real prompt delivery is connected.
- Reason: the composer needs to reserve the product shape for next-prompt
  controls while keeping this slice server-independent.
- Revisit when: server-backed prompt sending defines supported model, mode, and
  Thinking Effort option sources and request fields.

## Keep attachment as visual affordance only

- Date: 2026-05-09
- Decision: the composer includes a `+` attachment affordance labeled as
  `Attach context`, but it does not open a real attachment workflow in this
  scaffold.
- Reason: placement matters for the chat composer, but real attachment behavior
  may imply file upload or mobile IDE capabilities that remain outside this
  slice.
- Revisit when: context attachment behavior is explicitly designed within v1
  scope.
