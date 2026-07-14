# Review Changes

## User Goal

Open a dedicated mobile screen from Session Chat and understand what changed in
the latest turn, the current Git working tree, or the current branch relative
to its default branch.

## In Scope

- Add an always-available **Review changes** action to the Session Chat overflow
  menu.
- Add a dedicated **Changes** screen on iOS and Android.
- Support three Change Set sources:
  - **Last turn** for the latest completed user turn.
  - **Working tree** for current uncommitted Git changes.
  - **Main branch** for current branch changes relative to its default branch.
- Open Working tree from the Session Chat overflow menu when Git is available;
  otherwise fall back to Last turn.
- Show Main branch only when the server reports a current branch that differs
  from the default branch.
- Add a separate Review Changes Pill at the conversation end after a completed
  turn with changed files. The pill opens Last turn.
- Present one file at a time with a filterable changed-file picker, file status,
  addition/deletion counts, previous/next file navigation, and previous/next
  hunk navigation.
- Render a native unified diff with line numbers, syntax roles, horizontally
  scrollable long lines, collapsed context by default, and an expanded
  available-context mode.
- Allow files to be marked reviewed for the current viewer lifetime. Reviewed
  files remain visible and markers clear on source switch, refresh, or exit.
- Load a stable snapshot on entry or source switch and reload only through an
  explicit refresh or retry.

## Out Of Scope

- Editing files, applying patches, reverting changes, or executing shell
  commands.
- Live diff updates, event-driven refresh, or periodic polling while the viewer
  is open.
- Split diff layout.
- Persisting reviewed markers across viewer sessions or app launches.
- Full file browsing outside the selected Change Set.

## Server APIs Involved

- `GET /session/{id}/diff?messageID={latestUserMessageID}&directory={worktree}`
  for Last turn.
- `GET /vcs?directory={worktree}` for Git, current-branch, and default-branch
  availability.
- `GET /vcs/diff?mode=git&context=12&directory={worktree}` for Working tree.
- `GET /vcs/diff?mode=branch&context=12&directory={worktree}` for Main branch.

Unsupported or non-Git VCS endpoints leave Last turn available. Transient VCS
or session failures remain retryable and must not be treated as proof that a
source is unsupported.

## Expected Platform Logs

Log source, session identifier, directory, file, addition, and deletion totals,
request duration, and success or mapped failure for each load, source switch,
refresh, and pill-summary request. Do not log patch contents, source lines,
credentials, or authorization headers.

## Domain Objects

- **Change Set**: one loaded read-only snapshot from a selected source.
- **Change Source**: Last turn, Working tree, or Main branch.
- **File Diff**: file path, patch, additions, deletions, and
  added/deleted/modified status.
- **Diff Hunk** and **Diff Row**: native render models derived from a unified
  patch, including old/new line numbers and row meaning.

Generated or transport-specific types remain behind a Change Review Repository
so feature code uses the domain terms above.

## Screen Model

One immutable state exposes:

- loading, content, empty, and retryable error presentation;
- available and selected source;
- loaded snapshot time and aggregate counts;
- selected file and hunk;
- file filter and filtered results;
- collapsed or expanded context mode;
- viewer-local reviewed file paths.

Explicit actions are: load, retry, refresh, select source, select/filter file,
move to previous/next file, move to previous/next hunk, toggle context, toggle
reviewed, and navigate back.

The viewer captures the latest user-message identifier at entry. Foreground
events do not mutate an open snapshot.

## Native Navigation Entry Points

- Session Chat overflow: always show Review changes; request Working tree first
  and fall back to Last turn when VCS is unavailable.
- Review Changes Pill: show only when the timeline is at its end, the latest
  user turn is complete, and that turn has one or more changed files; open Last
  turn.
- The destination carries session identifier, worktree, initial source, and the
  captured latest user-message identifier through each platform's native
  navigation model.

## Error And Empty States

- Source-specific loading and retryable failure.
- No changes for Last turn, Working tree, or Main branch with distinct copy.
- No latest user turn.
- Non-Git or unsupported VCS source fallback.
- No files matching the current filter.
- Missing or empty patch preview, including binary files.
- A failed pill-summary request hides the pill without hiding the overflow entry.

## Permission Requests

Not applicable. Review Changes is read-only and does not approve permissions or
perform mutations.

## Design-System Components Needed

- Extended `OpenCodeReviewChangesPill`, documented in
  `docs/design-system/components/review-changes-pill.md`, with a compact latest
  turn file/addition/deletion summary and equivalent native accessibility.
- New reusable native `OpenCodeUnifiedDiff`, documented in
  `docs/design-system/components/unified-diff.md`.
- Reuse `OpenCodeTopBar`, `OpenCodeOverflowMenu`, `OpenCodeBottomSheet`,
  `OpenCodeListItem`, `OpenCodeButton`, semantic diff/syntax tokens, and the
  existing typography roles.
- Keep the source selector, aggregate toolbar, file-picker composition, and
  screen empty/error layouts feature-local because their behavior belongs to
  this screen rather than a general component role.

## Automated Acceptance Expectations

- Decode and map added, deleted, modified, optional-patch, binary, Unicode, and
  multi-hunk file diffs on both platforms.
- Verify worktree scoping, Last turn message identifiers, VCS modes, context,
  authentication, source availability, fallback, and mapped failures.
- Verify stable snapshots ignore foreground events and reload only on explicit
  actions.
- Verify filtering, selection, file/hunk navigation, context, reviewed-state
  lifecycle, horizontal scrolling, and empty/error recovery.
- Verify pill visibility, mutual exclusion with Scroll to end, singular/plural
  file copy, zero-valued line-total omission, navigation source, accessibility
  labels, semantic summary colors, and light/dark component states.
