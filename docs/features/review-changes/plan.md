# Review Changes

## User Goal

Open a mobile Changed Files list from Session Chat, choose between the latest
turn and current Git worktree changes, filter and understand the affected
files, then open one focused read-only file viewer for hunk-level inspection.

## In Scope

- Add an always-available Changes action to the Session Chat overflow.
- Show a Review Changes Pill after a completed turn with changed files.
- Support exactly two Change Set sources:
  - **Git changes** for current uncommitted worktree changes.
  - **Last turn changes** for the latest completed user turn.
- Open Git changes from the overflow when supported, otherwise fall back to Last
  turn changes. Open Last turn changes from the Review Changes Pill.
- Split Review Changes into two native screens:
  - a Changed Files screen for source selection, filtering, filtered totals,
    refresh, errors, empty states, and the stable snapshot;
  - a pushed File Viewer for one file's unified diff, hunk navigation, and
    collapsed/expanded context.
- Present filtered files in one grouped, bordered list. Rows show relative path,
  semantic `+N −N` counts including zero, and a chevron.
- Preserve the list's source, filter, and scroll position when returning from a
  file. Source switches preserve the filter and reset scroll.
- Clear the filter when the user exits the entire Changes flow back to Session
  Chat; filter restoration applies only while navigating within Changes.
- Keep pull-to-refresh on the list and keep an open viewer snapshot stable.
- Render native unified diffs with line numbers, syntax roles, horizontally
  scrollable long lines, collapsed context by default, and expanded available
  context.
- Show a bottom-centered, icon-only Hunk Navigation Pill with Previous hunk,
  Expand/Collapse all context, and Next hunk actions.
- Keep unrenderable files actionable through a Preview unavailable viewer state.

## Out Of Scope

- Main-branch comparison as a mobile Change Set source.
- Previous/next file stepping inside the viewer.
- Marking files reviewed or persisting review state.
- Editing files, applying patches, reverting changes, or executing shell
  commands.
- Live diff updates, event-driven refresh, or periodic polling while reviewing.
- Split diff layout, historical-turn browsing, or full file browsing outside
  the selected Change Set.

## Server APIs Involved

- `GET /session/{id}/diff?messageID={latestUserMessageID}&directory={worktree}`
  for Last turn changes.
- `GET /vcs?directory={worktree}` to confirm Git/worktree-diff availability.
- `GET /vcs/diff?mode=git&context=12&directory={worktree}` for Git changes.

The branch-diff mode is not used by this feature. Unsupported or non-Git VCS
capability leaves Last turn changes available. Transient failures remain
retryable and do not prove that Git changes are unsupported.

## Expected Platform Logs

Log source, session identifier, directory, file/addition/deletion totals,
request duration, and success or mapped failure for list load, source switch,
refresh, retry, and pill-summary requests. Do not log patches, source lines,
credentials, or authorization headers. File Viewer actions are local and do not
require network logs.

## Domain Objects

- **Change Set**: one stable read-only snapshot from a selected source.
- **Change Source**: Git changes or Last turn changes.
- **File Diff**: relative path, patch, additions, deletions, and status.
- **Diff Hunk** and **Diff Row**: native render models with old/new line numbers
  and semantic row meaning.
- **Changed Files State**: source, snapshot, filter, filtered totals, refresh,
  loading, empty, and error presentation.
- **File Viewer State**: selected file, typed document, selected hunk, context
  mode, action availability, and unavailable-preview presentation.

Generated and transport-specific types remain behind the Change Review
Repository so feature code uses the domain terms above.

## Screen Model

One flow-level owner retains the stable Change Set snapshot and Changed Files
state across native push/back navigation. The File Viewer references a path in
that snapshot and never reloads it.

Changed Files actions are load, retry, refresh, select source, update/clear
filter, open file, and navigate back. File Viewer actions are select previous
hunk, select next hunk, toggle all context, and navigate back.

Viewer-local hunk selection and context mode reset when the viewer exits. The
viewer captures the first hunk and collapsed context on entry. Foreground events
do not mutate the open snapshot.

## Native Navigation Entry Points

- Session Chat overflow: open Git changes when supported, otherwise Last turn
  changes.
- Review Changes Pill: open Last turn changes for the captured latest completed
  user-message identifier.
- File row: push File Viewer through each platform's native navigation model.
- Viewer Back, including native iOS swipe-back where supported: restore the
  existing Changed Files source, filter, snapshot, and scroll position.

Routes carry session identifier, worktree, initial source, captured message
identifier, and selected file path as needed. Patch content remains owned by the
flow rather than serialized into navigation routes.

## Error And Empty States

- Source-specific loading and retryable failure.
- No changes for Git changes or Last turn changes with distinct copy.
- No latest completed user turn.
- Confirmed unsupported/non-Git capability hides Git changes.
- No matching files shows zero filtered totals, No matching files, and Clear
  filter.
- Missing, malformed, empty, or binary patch opens Preview unavailable without
  the Hunk Navigation Pill.
- A failed Review Changes Pill summary request hides the pill without hiding
  the overflow entry.

## Permission Requests

Not applicable. Review Changes is read-only and does not approve permissions or
perform mutations.

## Design-System Components Needed

- Add reusable `OpenCodeListGroup` for the shared bordered container, clipping,
  corner radius, and internal row separators.
- Add reusable `OpenCodeHunkNavigationPill` for equal Previous, context, and
  Next icon actions, disabled states, pressed states, accessibility, galleries,
  and previews.
- Reuse `OpenCodeListItem` for each path row; the feature supplies semantic
  addition/deletion counts and the chevron in its trailing accessory.
- Reuse `OpenCodeUnifiedDiff` for typed hunks and selected-hunk focus; remove its
  reviewed state and presentation.
- Remove `OpenCodeFileNavigationPill`, its tokens, documentation, galleries,
  previews, and tests after its feature references are gone.
- Reuse OpenCodeTopBar, OpenCodeOverflowMenu, OpenCodeTextInput, icons, semantic
  diff/syntax tokens, and existing typography roles.
- Keep the filtered aggregate summary and screen empty/error compositions
  feature-local because they express Changed Files screen state.

## Automated Acceptance Expectations

- Decode and map added, deleted, modified, optional-patch, binary, Unicode, and
  multi-hunk file diffs on both platforms.
- Verify worktree scoping, latest-turn identifiers, Git availability, context,
  authentication, fallback, and mapped failures.
- Verify stable snapshots ignore foreground events and reload only from list
  load, source switch, refresh, or retry.
- Verify source order/defaults, filtered files and totals, clearing, empty/error
  recovery, list state restoration after File Viewer Back, and filter clearing
  when the Changes flow exits.
- Verify viewer entry reset, hunk boundaries, focus scrolling, all-hunk context
  toggle, no-expand disabling, horizontal scrolling, bottom inset, and
  unavailable preview.
- Verify grouped-list and Hunk Navigation Pill light/dark, enabled/disabled,
  accessibility, gallery, preview, and native integration states.
- Verify reviewed and previous/next file behavior are absent from state, UI,
  semantics, documentation, and tests.
- Verify Review Changes Pill visibility, Scroll-to-end mutual exclusion,
  summary copy/colors, entry source, and accessibility.

## Focused Execution Plan

