# Session Context Decisions

## Open-Time Snapshot

- Decision: Session Context loads when opened and refreshes only by pull-to-refresh.
- Reason: the user is inspecting details; live updates would move numbers while
  reading and duplicate Session Chat's live status behavior.

## Raw Message Rows

- Decision: Raw messages are rows only in this slice.
- Reason: mobile should expose the audit trail without the density and layout
  cost of expandable JSON payloads.

## Canonical APIs

- Decision: derive Session Context from canonical session, messages, and model
  provider APIs.
- Reason: `/api/session/{id}/context` returns active projected context events,
  not the screenshot-style token/accounting metrics.
