# Server API v2 — Decisions

Still binding:

- Detect `v1` | `v2` on connect. Route under remotes. Screen Models stay on
  domain types.
- Dual-stack HTTP stays (still talk to v1 servers).
- Live Session Chat tracks official TUI v2. Project `session.reasoning.*` /
  `session.next.reasoning.*` into a distinct reasoning part. Render thinking.
- `session.inbox.enqueued` / `.delivered` / `session.next.prompted` project
  to user `message.*`. Read `item.payload` (and `inboxID`).
- `session.next.step.ended` is not idle. Idle is execution succeeded /
  interrupted / failed.
- Tool, shell, compaction, and text `session.next.*` / `session.*` project
  into `message.part.*`.
- Capability-gate last-turn diff and archive when v2 has no twin.
- On v2, question list 404 falls back to forms. Keep `/api/question/*` when
  it works.
- V2 prompt is `{ text, files[{uri,name}], delivery }`. Do not send `mime`.
- Optimistic user rows stay until the timeline matches them.
