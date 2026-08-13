# Collapse Consecutive Tool Calls

Ticket: [OPE-144](https://linear.app/opencode-mobile/issue/OPE-144/collapse-consecutive-tool-calls)

## User Goal

Optionally collapse a run of back-to-back tool calls in Session Chat into one
summary Tool Call Card so long tool sequences stay scannable. Control this from
Settings → Appearance. Default remains expanded (today’s per-tool cards).

## In Scope

- Settings → Appearance toggle: **Collapse consecutive tool calls** (off by
  default).
- When the toggle is **on**, any run of two or more consecutive **visible**
  ToolParts in one assistant message becomes one **tool group** timeline block
  (aggregated summary card), regardless of tool family.
- When the toggle is **off**, keep current OPE-50 behavior: only consecutive
  context tools (`read` / `glob` / `grep`) auto-group; all other tools stay
  individual Tool Call Cards with specialized chrome and taps.
- Grouping still breaks on text blocks, attachments, hidden tools, or a
  non-tool part.
- Hide rules unchanged (`todowrite` always hidden; in-flight `question` hidden).
- Group status = worst-of members (error > running/pending > completed).
- Group title counts tools by family in encounter order (extend today’s
  context-group title style to mixed families).
- Group card is display-only (no per-member expand, no shell/network sheet, no
  Changes or child-session tap from the group). Turning the toggle off restores
  individual cards and their taps.
- Preference is device-local; cleared by Clear All Data.
- Pure grouping rules mirrored on iOS and Android with unit tests.
- Live rebuild: when parts/status update or the preference flips, re-project
  timeline blocks.

## Out Of Scope

- Expanding a group to show member cards in-place (accordion).
- Cross-message aggregation.
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
- Session Chat timeline (consumes preference; no new navigation).

## Error States

- Not applicable for preference storage beyond existing clear-data reset.
- Tool error status still surfaces on the group card via worst-of + first error
  text when present.

## Permission Behavior

- Not applicable.

## Design-System Components

- Existing Tool Call Card for both single tools and groups.
- Existing Settings list item + switch pattern.
- No new shared component.

## Product Decisions (locked)

| Choice | Value |
| --- | --- |
| What collapses | All consecutive visible tools in one message (not context-only) |
| Default | Off |
| Settings placement | Appearance |
| Single tool | Still a normal Tool Call Card (group only when ≥ 2) |
| Group interaction | Display-only summary; specialized taps require toggle off |
