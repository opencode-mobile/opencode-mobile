# Server API v2 — Decisions

Still binding:

- Detect `v1` | `v2` on connect. Route under remotes. Screen Models stay on
  domain types.
- Dual-stack HTTP **and** SSE is required, not transitional. v1 unprefixed
  remotes and v2 `/api/*` remotes both stay. Do not delete or skip a v1 path
  to “finish” v2, or the reverse.
- Live Session Chat **UX** tracks official TUI v2 on opencode **`origin/beta`**
  (`packages/tui` `stream-v2.transport.ts`, `GET /api/event`). That is the
  live event *shape*. Do not treat `dev`/`main` TUI as the v2 spec — and do
  not treat that as license to drop v1.
- Keep v1 `GET /global/event`, v1 instance `GET /event`, and v1 REST. Both
  protocols must deliver session lifecycle, message/part, status, permission,
  and question events over SSE. REST is reconnect / history, not a substitute
  for missing SSE. Heartbeat-only is not acceptable.
  Project `session.reasoning.*` /
  `session.next.reasoning.*` into a distinct reasoning part. Render thinking.
- `session.inbox.enqueued` / `.delivered` / `session.next.prompted` project
  to user `message.*`. Read `item.payload` (and `inboxID`).
- `session.next.step.ended` is not idle. Idle is execution succeeded /
  interrupted / failed.
- Tool, shell, compaction, and text `session.next.*` / `session.*` project
  into `message.part.*`. Later `session.tool.*` events omit `name`; project
  them as sparse overlays, not full replacements with invented identity.
  When `name` is omitted, infer from input (`query` → `websearch`,
  `filePath` → `read`) instead of leaving a hidden placeholder.
- Capability-gate last-turn diff and archive when v2 has no twin.
- On v2, question list 404 falls back to forms. Keep `/api/question/*` when
  it works.
- V2 prompt is `{ text, files[{uri,name}], delivery }`. Do not send `mime`.
- Optimistic user rows stay until the timeline matches them.
