# Assistant Tool Parts — Decisions

## Ticket slicing

- Keep domain enrichment and design-system Tool Call Card as parallel foundations.
- Ship generic tool cards in Session Chat before specialized families.
- Merge simple specialized families (context, shell, network, interact) into one
  lightweight chrome ticket.
- Keep file-change tools and subagent task as separate tickets (navigation and
  diff-stat presentation).

## OPE-48 domain shape (grilling)

- ToolPart is structured: tool status + name + title/error + opaque
  input/output/metadata (+ call id). Not a preformatted status string.
- Compact emoji labels are presentation via a display formatter; they are not
  stored on ToolPart.
- Missing or unrecognized server status coerces to `pending`.
- OPE-48 stores card-ready fields now so later tickets do not reshape the domain.
- Include call id when the server provides one.
- Until tool cards land, `Message.text` still joins TextPart text with
  formatter-built compact tool labels so Session Chat looks unchanged.
- Domain ToolPart omits `raw`, tool timing, and attachments for OPE-48; DTOs
  may still parse them. Android tool-state DTO gains `metadata` parity with iOS.
  OPE-158 later lifts `time.start` onto ToolPart as `timeStartEpochMs` for the
  quiet row.
- Compact label formatter stays domain-side (beside the message adapter) so
  both platforms keep the same `Message.text` projection until cards land.
- Completed edit/write/patch may already carry filediff-style metadata in
  OPE-48; compact labels stay dumb (`✓ edit`) until OPE-51 uses that metadata.
- Tool input and metadata are opaque string-keyed maps; output and error are
  optional strings. No per-tool domain schemas in OPE-48.

## OPE-47 Tool Call Card (grilling)

- Collapsed-only for this ticket: status, title, optional subtitle, optional
  +/− counts, optional error text, optional feature-owned tap. No expand,
  chevron, or body slot — add those when a later ticket needs them (likely
  OPE-50 for shell output).
- One component with four status variants (`pending` / `running` /
  `completed` / `error`). Do not ship a separate error card component.
- Pending/running use static status chrome only; no shimmer/animation in
  OPE-47.
- Optional +/− accessory is part of the public API now so OPE-51 does not
  reshape the card; the component does not compute diff stats.
- Card is tool-agnostic: feature code builds title/subtitle strings (for
  example “Called edit”). The design-system card does not know tool IDs.
- Optional tap is feature-owned; without a handler the card is display-only.
  The card does not navigate to Changes or child sessions itself.

## File-change v1

Superseded by **OPE-51 file-change tool cards (grilling)** below.

## Visibility

- Match desktop hide rules for `todowrite` and in-flight `question`.
- Live permission UX remains owned by Permission Approval. Live question-request
  UX is owned by Question Request (`docs/features/question-request/`).

## OPE-49 generic Session Chat cards (grilling)

- Keep one timeline item per **message**, with ordered **timeline blocks**
  inside (text block | tool card block). Do not flatten parts into sibling
  list rows.
- Session Chat builds blocks from structured parts. `Message.text` is
  TextPart-only (adjacent TextParts merged with newlines); tools are not
  joined into `text` for chat display.
- Hide rules live in the Screen Model when building blocks, not by dropping
  parts from the domain: always hide `todowrite`; hide `question` while
  pending/running; show completed `question` as generic until OPE-50.
- If a message has no visible blocks after hide rules, omit it from the
  timeline (no empty shell).
- OPE-49 chrome is generic for every tool ID (including `bash` / `edit` /
  `task`): title `Called {toolName}`; optional subtitle = running
  `ToolPart.title` only; error status keeps that title and uses the card
  error slot. No tap, expand, or input-key subtitle heuristics.
- Only assistant messages get tool card blocks; other roles stay text-only.
- Rebuild blocks from latest parts on each live apply (status updates in
  place). Markdown applies only to text blocks.
- Compact tool-label formatter leaves the Session Chat path; keep the helper
  until nothing else needs it, then delete.
- Specialize families in OPE-50 / OPE-51 / OPE-52; do not start that chrome
  in OPE-49.

## OPE-50 lightweight tool chrome (grilling)

- Keep all four simple families in one ticket: context, shell, network,
  interact. Do not split shell or grouping into a separate Linear issue.
  To-tickets confirmed: no further child split; OPE-50 remains the single
  implementable frontier ticket.
- Do not reshape the design-system Tool Call Card: stay collapsed-only with
  optional feature-owned tap. No expand, chevron, or body slot in OPE-50.
- Shell (`bash`) and completed network tools that have output or result links:
  tap opens the existing Bottom Sheet with that content. Pending/running bash
  is display-only (command as title/subtitle) until output or error text exists.
- Context (`read` / `glob` / `grep`): consecutive same-family tools in one
  message collapse into a single **tool group** timeline block (summary card
  with count). A text block or another tool family breaks the group. No
  per-tool expand inside a group in v1.
- Tool group status is worst-of its members: any `error` → error; else any
  `running` / `pending` → running; else completed. Rebuild on each live apply.
- Single (ungrouped) context copy: title = `Read` / `Glob` / `Grep`; subtitle =
  filename/path or path/pattern from opaque input; fall back to
  `Called {name}` when keys are missing; running `ToolPart.title` may override
  subtitle when present.
- Network: title = `Fetch` / `Search`; subtitle = URL or query. Result links
  for completed search live in the sheet on tap (plain text is enough; system
  open-URL optional). No new browser surface.
- Interact: keep hide rules. Completed `question` → title `Question`, subtitle
  from input when available. `skill` → title `Skill`, subtitle = skill name.
  No tap/navigation in OPE-50.
- Mapping and grouping live in the Screen Model / feature helpers only. No
  per-tool domain schemas (OPE-48 stands). Mirror the same pure rules on iOS
  and Android with tests.
- Tools outside these families (including `edit` / `write` / `apply_patch` /
  `task`) keep OPE-49 generic chrome until OPE-51 / OPE-52.
- Error status keeps specialized title/subtitle and uses the card error slot;
  sheet tap remains when error/output text exists.
- Per-block markdown (text after tools) stays out of OPE-50; leave the
  temporary parity gap.

## OPE-51 file-change tool cards (grilling)

- Product language: **file-change tool** = `edit` / `write` / `apply_patch`.
  Still a Tool Call Card, not a new design-system component. Not a Change Set.
- Collapsed chrome only: title = `Edit` / `Write` / `Patch`; subtitle =
  filename/path (or multi-file patch subtitle below); optional +/− from
  metadata. No card expand, no chevron body, no shimmer, **no Bottom Sheet**
  for the diff (sheets stay for OPE-50 shell/network output). No nested
  per-file accordion (web multi-file expand is not mirrored).
- Presentation choice (vs web inline accordion): tap opens a **full-screen**
  Changes page — File Viewer or Changed Files list per rules below — not an
  in-timeline expand and not a sheet.
- Field mapping stays feature-local on opaque maps (OPE-48 stands):
  - single-file path: `metadata.filediff.file` → input `filePath` / `path` /
    `file` (and single-entry `metadata.files` for `apply_patch`)
  - counts: `metadata.filediff.additions` / `deletions` (omit nil/zero sides)
  - missing path on single-file tools → specialized title, omit subtitle/
    counts, no tap
  - missing counts with path → show path, omit +/−
- `apply_patch` branches (align collapsed chrome with web, not expand UX):
  - **Single file** (one resolvable path): same as `edit` — path + optional
    +/−; tap → File Viewer when path is in the owning-turn snapshot, else list.
  - **Multi-file** (`metadata.files` length > 1): title `Patch`, subtitle
    `N files` (match web collapsed trigger); optional summed +/− when easy
    from file entries; tap → owning-turn **Changed Files list** (no File
    Viewer deep-link for an arbitrary file). No nested accordion.
- No consecutive file-change grouping (`edit`/`write`/`apply_patch` stay
  separate cards, same as web `CONTEXT_GROUP_TOOLS` excluding them).
- Error status keeps specialized title/subtitle/+/- when present and uses the
  card error slot.
- Tap (completed, or error when a resolvable single path / multi-file list
  target exists): open Changes on the **owning-turn** Change Set (`messageID`
  + `LastTurn`). Single-file: prefer **File Viewer** deep-link when the path
  exists in the snapshot; otherwise list. Multi-file patch: always list. Not
  latest-turn-only; not working-tree.
- Extend `ChangesNavigationTarget` with an optional initial file path (required
  for File Viewer deep-link; today the target only has session/worktree/
  messageId/source).
- Diff data for the viewer remains the turn Change Set API (existing File
  Viewer), not a separate tool-local before/after renderer in v1. Web’s
  tool-metadata accordion is intentionally not mirrored; see
  `docs/research/opencode-web-edit-tool-expand.md`.
- Pending/running: specialized chrome when path / “N files” is known, else
  title only; display-only (no tap).
- Blocked by OPE-50 (reuse `specializedChrome` + feature-owned tap; tap target
  is Changes/File Viewer navigation, not `detailText` sheet).
- Tests: chrome mapping (single + multi-file patch) + owning-turn nav with
  optional file deep-link vs list. Parity on iOS and Android.

## OPE-52 subagent / task tool card (grilling)

- Product language: a **`task` ToolPart** opens a **child session**. Do not add
  a first-class “subagent” domain entity; keep “subagent” informal only.
- Collapsed chrome only (no expand, sheet, or agent color): title = agent name
  (fallback `Task`); subtitle = description with optional ` (background)` when
  `metadata.background` is truthy; status uses existing Tool Call Card variants.
- Field mapping stays feature-local on opaque maps (OPE-48 stands):
  - title: `input.agent` → `input.name` → `metadata.agent` → `Task`
  - subtitle: `input.description` → `input.prompt` → running `ToolPart.title`
    → omit
  - nav: non-empty `metadata.sessionId`
- Timeline `ToolCard` optional `childSessionId`: non-null → push child Session
  Chat (same worktree); null → display-only. No preflight for stale children.
- No task grouping. Parallel with OPE-51.

## Running child session chat row (Quiet row)

[OPE-158](https://linear.app/opencode-mobile/issue/OPE-158/running-child-session-chat-row).
Locked from the running-child-session thread prototype, variant **A — Quiet
row**. A pending/running `task` in the parent timeline is a person/thread row,
not a Tool Call Card.

OPE-158 shipped this as feature-local Session Chat chrome. [OPE-162](https://linear.app/opencode-mobile/issue/OPE-162/design-system-child-session-row)
promotes that chrome to the design-system Child Session Row and uses it for
completed `task` parts as well. Error `task` parts still keep the OPE-52
Tool Call Card.

- Layout: circular letter avatar, agent name, optional `background` caption,
  live second line, optional elapsed, trailing chevron when tappable.
- No spinning progress, status ring, shimmer, or pulsing dots. “Still working”
  is the updating second line and elapsed time.
- Avatar is a letter from the agent name on a raised circle. Do not add a
  per-agent color palette (OPE-52 still forbids agent color as status chrome).
- Second line: running `ToolPart.title` when present, otherwise the task
  description / prompt. Do not subscribe to the child session’s tool stream
  just to populate this row.
- Elapsed: `m:ss` from a wall-clock start on the tool. Prefer `state.time.start`
  when it is already a real epoch. V2 live tool events omit that field; use the
  SSE envelope `created` next. Relative values such as `content.time.created: 10`
  are not epochs and must not become `0:00`. If both are missing, stamp local
  now once in the message store for a pending/running tool — never in the row
  builder, or rebuilds reset the clock. Keep the first resolved start across
  later `part.updated` and REST `replaceAll`. Reject a start more than ~2s in
  the future (device clock behind the server) and stamp local now instead.
  When a usable start is present, the quiet-row **view** ticks locally
  (`now − start`) about once a second so elapsed advances without rebuilding
  the Session Chat Screen Model. Do not run a ViewModel or timeline-builder
  timer. On resume, recompute from the stored start.
- Tap / `childSessionId` navigation stays as OPE-52.
- Design-system Child Session Row (OPE-162). Do not extend the Tool Call Card
  for this row; that component stays collapsed tool-agnostic status chrome.
  Session Chat keeps only the local elapsed tick and passes a formatted
  string.

## OPE-162 design-system Child Session Row

- Promote the Quiet row into the Component Collection. Completed `task` parts
  use the same row so they read as child sessions, not generic tool cards.
- Error `task` parts keep the Tool Call Card so the error slot stays visible.
- Elapsed stays feature-owned: the component does not tick. Pending/running
  pass `m:ss` from `time.start`; completed omits elapsed.
- Avatar is a letter from the title on `surface-raised`. No List Item
  `LetterAccent` palette.
