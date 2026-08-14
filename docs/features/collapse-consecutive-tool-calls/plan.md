# Collapse Consecutive Tool Calls

Ticket: [OPE-144](https://linear.app/opencode-mobile/issue/OPE-144/collapse-consecutive-tool-calls)

## User Goal

Optionally collapse a run of back-to-back **tool-call messages** in Session Chat
into one summary Tool Call Card. Some models emit Shell, Edit, Read, and similar
as one message after another; that stack is hard to scan. Control this from
Settings → Appearance. Default remains expanded (today’s per-tool cards).
`task` (child-session) cards never collapse.

## In Scope

- Settings → Appearance toggle: **Collapse consecutive tool calls** (off by
  default).
- When the toggle is **on**, a run of two or more consecutive **tool-call
  messages** (Shell, Edit, Read, Fetch, and other non-`task` tools) becomes
  one **tool group** timeline entry. This is the primary case: one tool per
  assistant message, stacked. Tools that happen to share one message also
  group. `task` cards never group so their child-session tap stays available.
- When the toggle is **off**, keep current OPE-50 behavior: only consecutive
  context tools (`read` / `glob` / `grep`) auto-group; all other tools stay
  individual Tool Call Cards with specialized chrome and taps.
- Grouping still breaks on visible text blocks, attachments, or a `task` tool.
  Hidden tools (`todowrite`, in-flight `question`), empty text, and compaction
  markers are skipped so they do not split a visible run.
- Hide rules unchanged (`todowrite` always hidden; in-flight `question` hidden).
- Group status = worst-of members (error > running/pending > completed).
  Status is the Tool Call Card **dot only** — no status-colored outline on the
  card.
- Group card copy (option E): title is `N tools`; subtitle is a family
  one-liner in encounter order (`Edit · Write · 5 shells`). A family of one is
  just the verb; multiples get a count. Human labels (`Shell`, not `bash`).
- Tap the group card to open the existing tool-detail sheet with each member’s
  verb, path or command, and error when present. No in-list accordion.
- Preference is device-local; cleared by Clear All Data.
- Pure grouping rules mirrored on iOS and Android with unit tests.
- Live rebuild: when parts/status update or the preference flips, re-project
  timeline blocks.

## Out Of Scope

- Expanding a group to show member cards in-place (accordion).
- Status-colored outline on the group card (status is the existing Tool Call
  Card dot only).
- Dumping full shell or network **output** into the group detail sheet.
- Cross-message aggregation across **user** turns, or across visible text /
  attachments / `task` tools.
- Grouping across text interruptions.
- Changing domain ToolPart schemas or server APIs.
- New design-system component (reuse Tool Call Card).
- Push / background sync.
- Per-session override of the preference.
- Remembering expand/collapse state per message.

## Server APIs Involved

- None. Presentation-only over existing message parts and Session Chat timeline.

## Expected Platform Logs

- Not applicable; no new network calls. Prefer existing Session Chat projection
  logs if present.

## Domain Objects

- Existing `MessagePart.Tool` / ToolPart and timeline `ToolGroup` / `ToolCard`.
- New device-local preference: collapse consecutive tool calls (boolean, default
  `false`). Prefer a small Session Chat display preferences store (or equivalent
  home/settings preference object) following existing preference patterns.

## UI States And Actions

- Settings Appearance: list row + switch for the toggle.
- Session Chat: timeline blocks reflect preference immediately after toggle
  (open chat re-projects; no app restart).
- Toggle off → context-only grouping (current).
- Toggle on → all-family consecutive grouping when count ≥ 2.

## Native Navigation Entry Points

- Settings → Appearance (alongside Hide User Directory / Recent window).
- Session Chat timeline (consumes preference). Group tap uses the existing
  tool-detail Bottom Sheet; no new navigation stack.

## Error States

- Not applicable for preference storage beyond existing clear-data reset.
- Tool error status still surfaces on the group card via the status **dot** and
  first error text when present. Member errors also appear in the detail sheet.

## Permission Behavior

- Not applicable.

## Design-System Components

- Existing Tool Call Card for both single tools and groups.
- Existing Settings list item + switch pattern.
- No new shared component.

## Product Decisions (locked)

| Choice | Value |
| --- | --- |
| What collapses | Consecutive tool-call messages (and consecutive tools in one message), except `task` |
| Default | Off |
| Settings placement | Appearance |
| Single tool | Still a normal Tool Call Card (group only when ≥ 2) |
| Group interaction | Tap opens the existing tool-detail sheet (concatenated member chrome; no shell output). Specialized taps (Changes, child session) still require toggle off |
| Group card copy | Title `N tools`; subtitle family one-liner in encounter order (option E) |
| Group status visual | Existing Tool Call Card status dot; hairline `border.weaker` — no status-colored outline |
