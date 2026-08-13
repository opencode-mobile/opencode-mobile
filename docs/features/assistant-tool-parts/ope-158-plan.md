# OPE-158 — Running child session chat row

## User outcome

A pending or running `task` in the parent Session Chat reads as a quiet
person/thread row. It is obvious a child session is working, and the row is
inviting to open, without spinning progress.

## Locked direction

Prototype variant **A — Quiet row**:
`docs/prototypes/running-child-session-thread/?variant=A`.

Recorded in `decisions.md` (Running child session chat row). Follow-up to
[OPE-52](https://linear.app/opencode-mobile/issue/OPE-52/subagent-task-tool-card).

## In scope

- Pending and running `task` ToolParts in the parent timeline use the quiet
  row: letter avatar, agent name, optional `background` caption, live second
  line, optional elapsed, trailing chevron when tappable.
- Second line: running `ToolPart.title` when present, otherwise description /
  prompt. Do not subscribe to the child session’s tool stream for this row.
- Elapsed: `m:ss` from existing tool `time.start` when present; omit when
  missing. Lift `time.start` into the domain only as needed for this row.
  Tick locally in the row view from that start; do not rebuild timeline
  blocks on a timer.
- Tap / `childSessionId` navigation unchanged from OPE-52.
- iOS and Android in the same change, with chrome + nav tests.

## Out of scope

- Spinners, status rings, shimmer, pulsing dots, nested tool feeds, composer
  docks, and per-agent color palettes.
- Changing completed or error `task` chrome (keep OPE-52 Tool Call Card).
- Extending the design-system Tool Call Card. This row is feature-local
  Session Chat chrome: Tool Call Card stays collapsed tool-agnostic status.
- New server APIs, invented start times, or a Screen Model / timeline-builder
  timer. A view-local tick from `time.start` is in scope.

## Design system

- Existing: none that match a person/thread row.
- New/extended: none in this ticket.
- Feature-local: quiet row in Session Chat. Reason: it is child-session
  presence, not generic tool status.

## Acceptance

- Pending/running `task` parts match Quiet row content and do not animate
  progress.
- Missing `time.start` omits elapsed rather than showing `0:00`.
- When `time.start` is present, elapsed advances locally while the row is
  visible.
- Missing `sessionId` stays readable and non-tappable.
- Completed/error `task` parts still use the Tool Call Card.
- iOS and Android chrome mapping and navigation stay in parity.
