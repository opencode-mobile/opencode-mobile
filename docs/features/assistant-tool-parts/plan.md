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
- Lightweight specialized chrome: context (`read`/`glob`/`grep` + grouping),
  shell (`bash`), network (`webfetch`/`websearch`), interact (`question` completed / `skill`).
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

- Session Chat timeline: ordered rows of text and tool cards (generic first;
  specialized chrome later).
- Actions: expand/collapse tool card where the shared card supports it; tap
  file-change tools → open existing Changes review; tap `task` → navigate to
  child session when `metadata.sessionId` is present.
- Until tool cards land, UI still renders flattened `Message.text` (compact
  labels) — no new Screen Model fields required for OPE-48 alone.

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
