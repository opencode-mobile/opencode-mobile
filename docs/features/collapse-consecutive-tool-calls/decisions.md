# Collapse Consecutive Tool Calls — Decisions

## Scope vs OPE-50

- OPE-50 hard-coded context-only grouping (`read` / `glob` / `grep`) and always
  on. This feature does **not** remove that baseline when the new toggle is off.
- When the toggle is on, grouping widens to **all** consecutive visible tool
  families, superseding the context-only look-ahead for those runs.

## Preference

- Default **off** so specialized chrome (shell sheet, file-change Changes tap,
  task → child session) stays available unless the user opts into denser chat.
- Store device-locally like other display prefs; include in Clear All Data.
- Place the control under Settings → Appearance.

## Aggregation semantics

- Operate on **timeline blocks inside one message**, not a new message role.
- Product language: **tool group** (existing term), not “aggregate message.”
- Minimum group size remains 2.
- Title style: count labels by tool family (`2 reads · 1 bash · 1 edit`),
  extending `contextGroupTitle` rather than a single “N tool calls” string when
  families are mixed.
- Status and error text follow existing tool-group worst-of rules.
- Stable `blockID` continues to use ordered member part IDs.

## Interaction trade-off

- Collapsed groups are display-only. Losing per-tool taps while collapsed is
  intentional; users who need taps leave the preference off.
- No in-group expand in this slice (same as OPE-50 context groups).

## Subagent (task) tools never group

- `task` (subagent spawn) tools are excluded from all-family collapse even when
  the toggle is on. They stay individual Tool Call Cards so the child-session
  tap remains available, and they break a group on either side.
- This narrows the ticket's "regardless of tool family" wording: subagent
  spawns are the one visible family that never collapses, because a
  display-only summary would strand the user from opening a running child
  session.

## Implementation seam

- Gate/widen grouping in the pure `SessionChatTimelineBlocks` builders on both
  platforms; pass the preference in (or read a thin display-preferences
  dependency) without putting preference knowledge in domain ToolPart.
- Feature UI and Settings follow existing preference + Screen Model patterns.

## Mixed-family title labels

- Title is encounter-order per family. Context families pluralize
  (`read`/`reads`, `grep`/`greps`, `glob`/`globs`); every other family keeps its
  raw tool name with no plural suffix (e.g. `2 reads · 1 bash · 1 edit`), since
  a generic `+s` rule mis-pluralizes families like `bash`, `webfetch`, and
  `apply_patch`. This matches the locked product example and keeps the title
  generator deterministic across platforms.
- The shared `groupTitle` is used by both the on and off paths, so pure-context
  groups now also render in encounter order instead of OPE-50's fixed
  `read → grep → glob`. This is an intended minor visual change; grouping
  behavior itself is unchanged when the toggle is off.
