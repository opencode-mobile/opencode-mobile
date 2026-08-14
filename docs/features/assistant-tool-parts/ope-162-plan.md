# OPE-162 — Design-system Child Session Row

## User outcome

Child-session `task` entries in the parent Session Chat use a reusable
design-system row. Completed tasks look like the same person/thread row as
pending and running ones, not a generic Tool Call Card.

## In scope

- Promote the OPE-158 Quiet row into `OpenCodeChildSessionRow`.
- Document the component contract, tokens, gallery, and previews.
- Replace feature-local chrome with the design-system component. Keep the
  Session Chat wrapper only for the local elapsed tick.
- Use the row for pending, running, and completed `task` parts.
- Error `task` parts keep the OPE-52 Tool Call Card.

## Out of scope

- Spinners, status rings, shimmer, pulsing dots, or per-agent color palettes.
- Putting a free-running clock in the design-system component.
- Changing Tool Call Card.
- New server APIs.

## Design system

- New: Child Session Row.
- Existing: Tool Call Card remains for error `task` parts and all other tools.

## Acceptance

- Gallery entries open on iOS and Android with interactive content controls.
- Pending/running `task` parts still match the Quiet row, including elapsed.
- Completed `task` parts use the same row (no status dot, no elapsed tick).
- Error `task` parts still use the Tool Call Card.
- Tap / `childSessionId` navigation is unchanged.
