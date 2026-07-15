# Assistant Tool Parts

## User goal

In Session Chat, assistant tool calls appear as readable native cards (not
flattened status lines), covering the main opencode tool families on iOS and
Android.

## In scope

- Structured tool parts in the domain (status, tool name, input/title/output/error/metadata).
- Design-system Tool Call Card with status variants.
- Generic tool cards in the Session Chat timeline for unknown tools.
- Hide `todowrite` and in-flight `question` (live ask stays on Permission Approval).
- Lightweight specialized chrome (OPE-50): context (`read`/`glob`/`grep` with
  consecutive **tool group** summary cards), shell (`bash` command + Bottom
  Sheet for output), network (`webfetch`/`websearch` + sheet for search
  results), interact (completed `question` / `skill`). Collapsed Tool Call Card
  only; no card expand API.
- File change tools (`edit`/`write`/`apply_patch`): filename + +N/−N; tap opens
  existing Changes review.
- Subagent `task` card with navigate to child session when available.

## Out of scope

- Full inline diff editor inside the chat timeline.
- Reasoning / step / snapshot / patch / compaction part rendering (separate work).
- New tunnel, push, or background sync behavior.
- Persistent permission `remember` from tool cards.

## Server APIs

- Existing session messages and part payloads (`type: "tool"`, ToolState).
- Foreground SSE part updates already delivered by Message Live Store.

## Domain objects

- `Message` / `MessagePart.Tool` (enriched).
- Session Chat Screen Model timeline rows that can interleave text and tool cards.

## Expected platform logs

- No new API call sites; reuse existing message fetch and foreground SSE part
  updates. Log only when Session Chat already logs part/message apply failures.
- Tool card taps that open Changes or a child session should use existing
  navigation logging if present; no new log channels for v1.

## Screen Model

- Session Chat timeline: one item per **message**, with ordered **timeline
  blocks** (text block | tool card block | tool group block). Generic cards
  first (OPE-49); specialized chrome in OPE-50+.
- OPE-49: Screen Model maps assistant parts → blocks; merges adjacent
  TextParts; applies hide rules (`todowrite`; in-flight `question`); omits
  messages with no visible blocks; rebuilds blocks on live part updates.
- OPE-50: feature helpers specialize title/subtitle for context/shell/network/
  interact; consecutive context tools become one tool group card; bash and
  completed network results use Bottom Sheet on tap; no Tool Call Card expand.
- Actions (later tickets): tap file-change tools → Changes review; tap
  `task` → child session when `metadata.sessionId` is present. OPE-49 generic
  cards are display-only; OPE-50 adds sheet taps only where noted.
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

- Live `question` / permission approval remains owned by Permission Approval.
- In-flight `question` tools stay hidden in the timeline; completed `question`
  may show as interact chrome later.
- Do not set persistent permission `remember` from tool cards.

## Design system

- **New:** Tool Call Card (status, title, subtitle, optional +/− / error).
- Reuse existing Changes navigation for file-change taps.
- Feature-local mapping from tool ID → card chrome only where the shared card
  variants are insufficient.

## Research

- `docs/research/opencode-assistant-tool-messages.md`
- `docs/research/opencode-assistant-messages.md`
