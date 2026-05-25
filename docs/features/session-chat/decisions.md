# Session Chat Decisions

## Start with local Screen Model scaffold

- Date: 2026-05-07
- Decision: first Session Chat implementation uses local/mock state for messages
  and composer actions on both platforms.
- Reason: unblock session-to-chat navigation and shared interaction framing before
  server integration is introduced.
- Revisit when: session history, prompt send, and stream endpoints are connected.

## Use local composer selectors in scaffold

- Date: 2026-05-09 (interaction clarified 2026-05-10)
- Decision: Session Chat composer shows local selector chips for model, mode, and
  Thinking Effort before real prompt delivery is connected. Tapping a chip opens
  **`OpenCodeBottomSheet`** to pick an explicit value instead of cycling options in
  place.
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

## WhatsApp-style timeline order and scroll anchor

- Date: 2026-05-24
- Decision: Session Chat lists messages oldest-to-newest (`time.created` ascending,
  then message id). Opening a session or receiving a new bottom message scrolls
  to the latest entry; pull-to-refresh does not jump when the latest message is
  unchanged.
- Reason: chat products anchor on the most recent message at the bottom; server
  order is usually chronological but the apps enforce sort and scroll explicitly.
- Revisit when: read-position preservation is needed while the user has scrolled
  up into history (only auto-scroll when already near the bottom).
