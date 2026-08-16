# Collapse Consecutive Tool Calls — Decisions

## Scope vs OPE-50

- OPE-50 hard-coded context-only grouping (`read` / `glob` / `grep`) and always
  on. This feature does **not** remove that baseline when the new toggle is off.
- When the toggle is on, grouping widens to **all** consecutive visible tool
  families, superseding the context-only look-ahead for those runs.

## Preference

- Default **on**. Specialized chrome stays on member cards inside the tool
  group screen (shell sheet, file-change Changes tap). `task` still never
  groups. Users can turn the preference off under Settings → Appearance.
- Store device-locally like other display prefs; include in Clear All Data.
- Place the control under Settings → Appearance.

## Aggregation semantics

- Operate on **visible timeline blocks**, not raw part arrays.
- Product language: **tool group** (existing term), not “aggregate message.”
- Minimum group size remains 2.
- Title is the count headline (`7 tools`). Subtitle is a family one-liner in
  encounter order (`Edit · Write · 5 shells`). A family of one is just the
  verb; multiples get a count. Human labels (`Shell`, not `bash`).
- Status and error text follow existing tool-group worst-of rules. Status is
  the Tool Call Card **dot only** — do not paint a warning/error outline on
  the card.
- Stable `blockID` continues to use ordered member part IDs.
- Hidden tools, empty/non-displayable text, and compaction parts are
  transparent: grouping looks through them (web filters to renderable parts
  before grouping).
- Visible text, attachments, and `task` tools still break a group.

## Sequential tool-call messages are the product

- The original “parts inside one message” framing was the wrong problem. When
  several tools share one assistant message they already sit in one timeline
  item; some models instead emit **message after message**, each a single
  Shell / Edit / Read / etc. That stack is what collapse is for.
- When collapse is on, consecutive **tool-only** assistant messages merge into
  one tool group (mixed families allowed). `task` never joins and splits a run.
- Visible assistant text, user messages, and attachments still break a run.
- Collapse off still groups only within a message (OPE-50 context-only).
- Merged rows keep the first message’s identity; absorbed messages drop out of
  the timeline.

## Interaction trade-off (option E)

- The group card is compact: `N tools` plus the family one-liner. No in-list
  accordion.
- Tap **opens** a full-screen tool group (back, title, family subtitle, member
  Tool Call Cards). Both platforms push it as a real navigation destination
  (iOS `NavigationStack`, Android Navigation 3) so Back and the platform
  swipe/predictive-back gesture pop the group like Changes. Session Chat stays
  on the stack, so the timeline keeps its scroll position. Later pushes such
  as Changes stack on top of the group. This is not an overlay and not a
  concatenated bottom sheet.
- Member cards reuse the same taps as timeline cards: file-change tools open
  Changes; shell/network tools open the existing tool-detail sheet (command
  plus output). The group screen itself does not dump full output.
- `task` never joins a group so its child-session tap stays on the timeline.
- Status color stays on the dot. A status-colored card outline was rejected:
  the hairline is always `border.weaker`.

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

## Mixed-family subtitle labels

- Subtitle is encounter-order per family with a small phrase map so labels stay
  human (`Shell` / `N shells`, `Fetch` / `N fetches`, `Get issue` /
  `N Get issues`). Unknown names fall back to the raw tool name.
- Count 1 is the verb alone (`Edit`); count > 1 includes the number
  (`5 shells`). Joiner is ` · `.
- The same generators are used by both the on and off paths, so pure-context
  groups now also render as `N tools` / `Read · Grep · Glob` (or `2 reads`)
  instead of OPE-50's census title and fixed `read → grep → glob` order. This is
  an intended visual change; grouping behavior itself is unchanged when the
  toggle is off.
