# Assistant Tool Parts

## User goal

In Session Chat, assistant tool calls appear as readable native cards (not
flattened status lines), covering the main opencode tool families on iOS and
Android.

## In scope

- Structured tool parts in the domain (status, tool name, input/title/output/error/metadata).
- Design-system Tool Call Card with status variants.
- Generic tool cards in the Session Chat timeline for unknown tools.
- Hide `todowrite` and in-flight `question` (live ask stays on Question Request).
- Lightweight specialized chrome (OPE-50): context (`read`/`glob`/`grep` with
  consecutive **tool group** summary cards), shell (`bash` command + Bottom
  Sheet for output), network (`webfetch`/`websearch` + sheet for search
  results), interact (completed `question` / `skill`). Collapsed Tool Call Card
  only; no card expand API.
- File change tools (`edit`/`write`/`apply_patch`): filename or `N files` +
  +N/−N; tap opens owning-turn Changes (File Viewer deep-link or list).
- `task` tool card with navigate to child session when available. Pending,
  running, and completed `task` parts use the design-system Child Session Row
  (OPE-158 Quiet row, promoted in OPE-162). Error `task` parts keep the
  OPE-52 Tool Call Card.

## Out of scope

- Full inline diff editor inside the chat timeline.
- Reasoning / step / snapshot / patch / compaction part rendering (separate work).
- New tunnel, push, or background sync behavior.
- Persistent permission `remember` from tool cards.

## Server APIs

- Existing session messages and part payloads (`type: "tool"`, ToolState).
- Foreground SSE part updates already delivered by Message Live Store.

## Domain objects

- `Message` / `MessagePart.Tool` (enriched), including optional
  `timeStartEpochMs` from tool `time.start` for the OPE-158 quiet row.
- Session Chat Screen Model timeline rows that can interleave text, tool
  cards, tool groups, and child session rows.

## Expected platform logs

- No new API call sites; reuse existing message fetch and foreground SSE part
  updates. Log only when Session Chat already logs part/message apply failures.
- Tool card taps that open Changes or a child session should use existing
  navigation logging if present; no new log channels for v1.

## Screen Model

- Session Chat timeline: one item per **message**, with ordered **timeline
  blocks** (text block | tool card block | tool group block | child session
  row). Generic cards first (OPE-49); specialized chrome in OPE-50+.
- OPE-49: Screen Model maps assistant parts → blocks; merges adjacent
  TextParts; applies hide rules (`todowrite`; in-flight `question`); omits
  messages with no visible blocks; rebuilds blocks on live part updates.
- OPE-50: feature helpers specialize title/subtitle for context/shell/network/
  interact; consecutive context tools become one tool group card; bash and
  completed network results use Bottom Sheet on tap; no Tool Call Card expand.
- Actions: OPE-50 sheet taps where noted; OPE-51 file-change → owning-turn
  File Viewer (single-file path deep-link) or Changed Files list (multi-file
  patch / missing path in snapshot);   OPE-52 `task` → child session when
  `metadata.sessionId` is present; OPE-158 / OPE-162 pending, running, and
  completed `task` parts use the Child Session Row (same tap). Error `task`
  parts keep the Tool Call Card. OPE-49 generic cards remain display-only.
- Chat display uses blocks; `Message.text` is TextPart-only.

## Native navigation entry points

- Session Chat timeline (both apps) is the only entry for tool cards.
- File-change tools reuse the existing Changes / review-changes navigation.
- `task` tools navigate into an existing child session chat route when available.

## Error states

- Tool `error` status: show error variant on the Tool Call Card (tool name +
  cleaned error text). Compact `Message.text` continues to use `✕ name: message`
  until cards land.
- Transport/decode failures stay on the existing Session Chat error surfaces;
  tool cards do not introduce a separate error channel.

## Permission behavior

- Live permission approval remains owned by Permission Approval. Live question
  requests remain owned by Question Request.
- In-flight `question` tools stay hidden in the timeline; completed `question`
  may show as interact chrome later.
- Do not set persistent permission `remember` from tool cards.

## Design system

- **Existing:** Tool Call Card (status, title, subtitle, optional +/− / error).
- **New:** Child Session Row (letter avatar, title, optional caption /
  subtitle / elapsed, chevron when tappable).
- Reuse existing Changes navigation for file-change taps (owning-turn File
  Viewer or Changed Files list).
- Feature-local mapping from tool ID → card or row chrome only where the
  shared variants are insufficient. The elapsed tick stays in Session Chat;
  the component takes a formatted string.
- **Feature-local:** Quiet row for pending/running `task` (OPE-158). Not a
  Tool Call Card variant; it is child-session presence, not generic tool
  status.

## Research

- `docs/research/opencode-assistant-tool-messages.md`
- `docs/research/opencode-assistant-messages.md`
